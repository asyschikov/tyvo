package com.tyvo.keyboard.ime

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.tyvo.keyboard.actions.QuickAction
import com.tyvo.keyboard.audio.AudioRecorder
import com.tyvo.keyboard.data.Settings
import com.tyvo.keyboard.history.Maintenance
import com.tyvo.keyboard.history.Notifications
import com.tyvo.keyboard.history.RecordingStore
import com.tyvo.keyboard.usage.UsageStore
import com.tyvo.keyboard.net.TyvoException
import com.tyvo.keyboard.polish.Polisher
import com.tyvo.keyboard.settings.SettingsActivity
import com.tyvo.keyboard.transcribe.Transcriber
import com.tyvo.keyboard.ui.KeyboardView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * The keyboard.
 *
 * Deliberately not a general-purpose typing keyboard: it dictates, cleans up,
 * and then offers to reshape what it just wrote. For ordinary typing the user
 * switches back to their usual IME via the globe key.
 */
class TyvoInputMethodService : InputMethodService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private companion object {
        /** Marks a variant produced by a typed instruction rather than a chip. */
        const val CUSTOM_ACTION_ID = "__custom__"

        /** How far back to look when deleting a word. */
        const val WORD_LOOKBEHIND = 96

        /**
         * Substrings that mark a typed instruction as a translation request,
         * in the languages most likely to be dictated here.
         */
        val TRANSLATION_HINTS = listOf(
            "translat", "in english", "to english", "in german", "to german",
            "in french", "to french", "in spanish", "to spanish",
            "in russian", "to russian", "перевед", "перевод", "по-английски",
            "на английский", "на русский", "übersetz", "traduis", "traduce",
        )
    }

    private lateinit var settings: Settings
    private lateinit var recorder: AudioRecorder
    private lateinit var transcriber: Transcriber
    private lateinit var polisher: Polisher
    private lateinit var store: RecordingStore
    private lateinit var usage: UsageStore

    /** Most recent failed recording, retryable straight from the keyboard. */
    private var lastFailedId: String? = null

    private val session = DictationSession()
    private var view: KeyboardView? = null

    private var pipeline: Job? = null
    private var ticker: Job? = null

    /**
     * Text this service committed, used to detect whether the field still
     * holds our text before we try to replace it.
     */
    private var committed: String = ""

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        recorder = AudioRecorder(cacheDir)
        store = RecordingStore(this)
        // Before the two clients: they hold a reference to it.
        usage = UsageStore(this)
        transcriber = Transcriber(settings, usage)
        polisher = Polisher(settings, usage)
        Maintenance.runInBackground(store, settings)
    }

    override fun onCreateInputView(): View {
        val v = KeyboardView(this)
        v.onMicPressed = { toggleRecording() }
        v.onAction = { runAction(it) }
        v.onUndo = { undo() }
        v.onAccept = { acceptSession() }
        v.onRepolish = { repolish() }
        v.onUnpolish = { unpolish() }
        v.onRetry = { retryLast() }
        v.onOpenSettings = { openSettings() }
        v.onSwitchKeyboard = { switchAway() }
        v.onBackspace = { byWord -> backspace(byWord) }
        v.onNewline = { commitLiteral("\n") }
        v.onSpace = { commitLiteral(" ") }
        v.onCustomInstruction = { runCustom(it) }
        view = v
        render(UiState.Idle())
        return v
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // A new field means the previous session's text is no longer ours.
        if (!restarting) {
            session.clear()
            committed = ""
        }
        render(stateForIdle())
        view?.setMicEnabled(hasMicPermission())
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        abortRecording()
    }

    override fun onDestroy() {
        abortRecording()
        scope.cancel()
        super.onDestroy()
    }

    // ---- Recording ------------------------------------------------------

    private fun toggleRecording() {
        if (recorder.isRecording) stopAndProcess() else startRecording()
    }

    private fun startRecording() {
        if (!hasMicPermission()) {
            render(UiState.Idle("Grant microphone access in Tyvo settings."))
            openSettings()
            return
        }
        if (!hasTranscribeKey()) {
            render(UiState.Idle("Add an API key in Tyvo settings."))
            openSettings()
            return
        }

        pipeline?.cancel()
        val file = recorder.start(onAutoStop = {
            // Hit the duration ceiling on the recorder thread.
            scope.launch { stopAndProcess() }
        })
        if (file == null) {
            render(UiState.Idle("Microphone unavailable."))
            return
        }

        haptic()
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive && recorder.isRecording) {
                val elapsed = System.currentTimeMillis() - recorder.startedAtMs
                view?.setAmplitude(recorder.amplitude)
                render(UiState.Recording(elapsed))
                delay(60)
            }
        }
    }

    private fun stopAndProcess() {
        if (!recorder.isRecording) return
        ticker?.cancel()
        val durationMs = System.currentTimeMillis() - recorder.startedAtMs
        val file = recorder.stop()
        haptic()
        if (file == null) {
            // A stray tap should not throw away text still under review.
            render(
                if (session.isActive) reviewOrIdle(note = "Too short.")
                else UiState.Idle("Too short - speak while the mic is active.")
            )
            return
        }
        process(file, durationMs)
    }

    private fun abortRecording() {
        ticker?.cancel()
        if (recorder.isRecording) recorder.cancel()
    }

    // ---- Pipeline -------------------------------------------------------

    /**
     * Transcribes a finished recording and puts the result in the field.
     *
     * The recording is handed to the store *before* the network is touched,
     * so a dead connection, a crash, or the system killing us mid-request
     * cannot destroy what the user said. The audio is deleted only once text
     * exists to replace it.
     */
    private fun process(audio: File, durationMs: Long) {
        val recording = try {
            store.addPending(audio, durationMs)
        } catch (e: Exception) {
            // Even if indexing fails, still try to transcribe rather than
            // dropping the dictation on the floor.
            null
        }
        transcribeRecording(recording?.id, recording?.let { store.audioFor(it) } ?: audio)
    }

    /** Runs transcription plus the polish pass for one stored recording. */
    private fun transcribeRecording(recordingId: String?, audio: File) {
        pipeline?.cancel()
        pipeline = scope.launch {
            try {
                render(UiState.Working("Transcribing"))
                val raw = transcriber.transcribe(audio)
                if (raw.isBlank()) {
                    // Nothing heard is a real outcome, not a failure: keep the
                    // audio so the user can check for themselves.
                    recordingId?.let { store.markFailed(it, "No speech detected.") }
                    lastFailedId = recordingId
                    notifyFailure("No speech detected.")
                    render(UiState.Idle("Nothing heard. Saved to Tyvo.", canRetry = true))
                    return@launch
                }

                recordingId?.let { store.markDone(it, raw) }
                lastFailedId = null

                val shouldPolish = settings.autoPolish && settings.polishEnabled
                if (!shouldPolish) {
                    commitFresh(raw)
                    render(reviewOrIdle())
                    return@launch
                }

                // Nothing is inserted until the text is final. Committing the
                // raw transcript first and swapping it a second later puts
                // visibly wrong words in someone's message and rewrites them
                // under the cursor, which reads as a glitch even though it is
                // faster.
                render(UiState.Working("Cleaning up"))
                val polished = try {
                    polisher.cleanUp(raw)
                } catch (e: TyvoException) {
                    // Clean-up failed, but the words are good: insert them
                    // rather than making the user dictate again.
                    commitFresh(raw)
                    render(reviewOrIdle(note = e.message))
                    return@launch
                }

                commitFresh(polished, transcript = raw)
                if (polished != raw) {
                    recordingId?.let { store.updateText(it, polished) }
                }
                render(reviewOrIdle())
            } catch (e: TyvoException) {
                failRecording(recordingId, e.message ?: "Transcription failed.")
            } catch (e: Exception) {
                failRecording(recordingId, "Transcription failed.")
            }
        }
    }

    /**
     * Reports a failed transcription without losing the audio.
     *
     * The keyboard offers an immediate retry, and a notification points at
     * the app for later -- the recording is safe either way.
     */
    private fun failRecording(recordingId: String?, message: String) {
        recordingId?.let { store.markFailed(it, message) }
        lastFailedId = recordingId
        if (recordingId != null) notifyFailure(message)
        render(
            UiState.Idle(
                lastError = if (recordingId != null) "$message Saved - tap Retry." else message,
                canRetry = recordingId != null,
            )
        )
    }

    /** Retries the most recent failure, straight from the keyboard. */
    private fun retryLast() {
        val id = lastFailedId ?: return
        val rec = store.get(id) ?: return
        val audio = store.audioFor(rec) ?: run {
            render(UiState.Idle("Recording is no longer available."))
            lastFailedId = null
            return
        }
        transcribeRecording(id, audio)
    }

    /**
     * Tells the user a dictation was saved rather than lost.
     *
     * Two channels because they cover different moments: the toast is seen
     * now, while they are still looking at the field, and the notification is
     * found later, once they have wandered off. Both are optional, and the
     * keyboard's own status line carries the message regardless.
     */
    private fun notifyFailure(message: String) {
        if (settings.failureToasts) {
            runCatching {
                Toast.makeText(this, "$message Saved to Tyvo.", Toast.LENGTH_LONG).show()
            }
        }
        if (settings.failureNotifications) {
            Notifications.transcriptionFailed(this, store.needingAttention().size)
        }
    }

    private fun runAction(action: QuickAction) =
        runInstruction(action.instruction, action.label, action.id, action.translating)

    private fun runCustom(instruction: String) =
        // A typed instruction may legitimately ask for a translation, so the
        // language lock is relaxed only when the user actually said so.
        runInstruction(
            instruction,
            "Rewriting",
            CUSTOM_ACTION_ID,
            translating = looksLikeTranslation(instruction),
        )

    /** Whether a typed instruction is asking for a language change. */
    private fun looksLikeTranslation(instruction: String): Boolean {
        val s = instruction.lowercase()
        return TRANSLATION_HINTS.any { it in s }
    }

    /**
     * Applies an instruction to the session's base text.
     *
     * Deliberately not to whatever is currently in the field: chaining
     * rewrites compounds the model's drift, so tapping Formal then Shorter
     * gives a short version of the original, not a short version of the
     * formal rewrite.
     */
    private fun runInstruction(
        instruction: String,
        label: String,
        actionId: String,
        translating: Boolean,
    ) {
        val source = session.base
        if (source.isBlank()) return
        pipeline?.cancel()
        pipeline = scope.launch {
            render(reviewOrIdle(note = label, busy = true))
            try {
                val result = polisher.transform(
                    text = source,
                    instruction = instruction,
                    translating = translating,
                )
                if (replaceCommitted(result)) {
                    session.setVariant(result, actionId)
                }
                render(reviewOrIdle())
            } catch (e: TyvoException) {
                render(reviewOrIdle(note = e.message))
            } catch (e: Exception) {
                render(reviewOrIdle(note = "Rewrite failed."))
            }
        }
    }

    /**
     * Produces a fresh polished version and makes it the new base.
     *
     * Always cleans up the raw transcript rather than the current text, so
     * re-polishing after an unpolish gives a genuine second attempt instead
     * of polishing an already-polished sentence.
     */
    private fun repolish() {
        val source = session.rawTranscript.ifBlank { session.base }
        if (source.isBlank()) return
        pipeline?.cancel()
        pipeline = scope.launch {
            render(reviewOrIdle(note = "Polishing", busy = true))
            try {
                val polished = polisher.cleanUp(source)
                if (replaceCommitted(polished)) {
                    session.setPolished(polished)
                }
                render(reviewOrIdle())
            } catch (e: TyvoException) {
                render(reviewOrIdle(note = e.message))
            } catch (e: Exception) {
                render(reviewOrIdle(note = "Clean-up failed."))
            }
        }
    }

    /**
     * Drops the polished version and returns to what was actually said.
     *
     * Lossy on purpose: the polished text is discarded, and re-polishing
     * afterwards produces a new version rather than restoring this one.
     */
    private fun unpolish() {
        val rawText = session.unpolish() ?: return
        replaceCommitted(rawText)
        haptic()
        render(reviewOrIdle())
    }

    /** Drops the applied quick action, returning to the base text. */
    private fun undo() {
        val restored = session.undo() ?: return
        replaceCommitted(restored)
        haptic()
        render(reviewOrIdle())
    }

    private fun acceptSession() {
        session.clear()
        committed = ""
        render(UiState.Idle())
    }

    // ---- Text commit ----------------------------------------------------

    /**
     * Inserts newly dictated text, replacing the selection if there is one.
     * Adds a leading space when joining onto existing prose.
     */
    /**
     * Inserts newly dictated text and starts a session on it.
     *
     * [transcript] is what was actually said, which the session keeps so
     * clean-up can be undone. It differs from [text] when the inserted
     * version has already been polished.
     */
    private fun commitFresh(text: String, transcript: String = text) {
        val ic = currentInputConnection ?: return
        val spaced = if (needsLeadingSpace(ic)) " $text" else text
        ic.beginBatchEdit()
        ic.commitText(spaced, 1)
        ic.endBatchEdit()
        committed = spaced
        // Seed with the transcript, then promote the polished text, so
        // "Undo clean-up" still has something to fall back to.
        session.begin(transcript)
        if (transcript != text) session.setPolished(spaced)
    }

    /**
     * Swaps our previously committed text for [text].
     *
     * Deletes exactly as many characters as we put in, so text the user typed
     * around ours is untouched. If the field no longer ends with our text --
     * the user edited it meanwhile -- we append instead of destroying their
     * edit.
     */
    /**
     * Swaps our previously committed text for [text].
     *
     * Returns false without touching the field if what sits before the cursor
     * is no longer exactly what we committed -- the user typed, moved the
     * caret, or the app rewrote the field. Appending in that case would leave
     * both versions in the field, which is worse than declining the edit.
     */
    private fun replaceCommitted(text: String): Boolean {
        val ic = currentInputConnection ?: return false
        val prior = committed
        if (prior.isEmpty()) return false

        val leading = prior.takeWhile { it == ' ' }
        val replacement = leading + text.trimStart()

        ic.beginBatchEdit()
        val before = ic.getTextBeforeCursor(prior.length, 0)?.toString()
        val matched = before == prior
        if (matched) {
            ic.deleteSurroundingText(prior.length, 0)
            ic.commitText(replacement, 1)
            committed = replacement
        }
        ic.endBatchEdit()

        if (!matched) {
            // Our text is no longer ours to edit; end the session cleanly.
            session.clear()
            committed = ""
        }
        return matched
    }

    private fun needsLeadingSpace(ic: InputConnection): Boolean {
        val prev = ic.getTextBeforeCursor(1, 0)?.toString()?.lastOrNull() ?: return false
        // Join onto a word or a closing mark, but not onto an opening bracket,
        // an existing space, or a newline.
        return prev.isLetterOrDigit() || prev in ".,!?;:\")"
    }

    /**
     * Backspace.
     *
     * When text is selected the selection is what should go, but
     * deleteSurroundingText only ever removes characters adjacent to the
     * cursor and silently does nothing while a selection exists. So we check
     * for one first and replace it with an empty string.
     *
     * Falls back to a real key event when we cannot read the selection, which
     * is the case in a few webview-backed fields.
     */
    /**
     * Deletes one character, or a whole word once a held backspace has been
     * repeating for a while.
     *
     * [byWord] is what the accelerating repeat escalates to: holding delete
     * to clear a sentence one letter at a time is slow enough that people
     * give up and reach for the text field instead.
     */
    private fun backspace(byWord: Boolean = false) {
        val ic = currentInputConnection ?: return
        // Editing invalidates the session: our committed text no longer matches.
        session.clear()
        committed = ""

        val selected = ic.getSelectedText(0)
        if (selected != null && selected.isNotEmpty()) {
            ic.commitText("", 1)
        } else if (byWord) {
            deleteWordBefore(ic)
        } else {
            val before = ic.getTextBeforeCursor(2, 0)
            if (before == null) {
                // Selection state unknown; let the editor apply its own rules.
                sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            } else {
                // Delete a whole surrogate pair so emoji vanish in one tap.
                val n = if (before.length >= 2 &&
                    Character.isSurrogatePair(before[0], before[1])
                ) 2 else 1
                ic.deleteSurroundingText(n, 0)
            }
        }
        render(UiState.Idle(canRetry = lastFailedId != null))
    }

    /**
     * Deletes the word before the cursor, plus the whitespace that trailed it.
     *
     * Looks back a bounded window rather than the whole field: the text can
     * be arbitrarily long, and a word is never far away.
     */
    private fun deleteWordBefore(ic: InputConnection) {
        val before = ic.getTextBeforeCursor(WORD_LOOKBEHIND, 0)
        if (before.isNullOrEmpty()) {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            return
        }
        var i = before.length
        // Trailing whitespace goes with the word, so one hold does not stall
        // on the space after it.
        while (i > 0 && before[i - 1].isWhitespace()) i--
        if (i > 0) {
            val letters = before[i - 1].isLetterOrDigit()
            while (i > 0 && !before[i - 1].isWhitespace() &&
                before[i - 1].isLetterOrDigit() == letters
            ) i--
        }
        val count = before.length - i
        ic.deleteSurroundingText(if (count > 0) count else 1, 0)
    }

    /**
     * Types a literal character.
     *
     * Ends the session: once the user puts their own text in the field, the
     * committed text is no longer the last thing there, so the quick actions
     * would have nothing safe to replace.
     */
    private fun commitLiteral(text: String) {
        val ic = currentInputConnection ?: return
        ic.commitText(text, 1)
        session.clear()
        committed = ""
        render(UiState.Idle(canRetry = lastFailedId != null))
    }

    // ---- Misc -----------------------------------------------------------

    /**
     * Review while we still own text in the field, otherwise idle. Used after
     * every edit so a session invalidated mid-flight collapses gracefully
     * instead of offering actions that would do nothing.
     */
    private fun reviewOrIdle(note: String? = null, busy: Boolean = false): UiState =
        if (session.isActive) {
            UiState.Review(
                text = session.current,
                canUndo = session.canUndo,
                canUnpolish = session.canUnpolish,
                isPolished = session.isPolished,
                activeAction = session.activeAction,
                busy = busy,
                note = note,
            )
        } else {
            UiState.Idle(note ?: "Text is no longer editable here.")
        }

    private fun stateForIdle(): UiState =
        if (session.isActive) reviewOrIdle()
        else UiState.Idle(canRetry = lastFailedId != null)

    private fun render(state: UiState) {
        view?.render(state)
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasTranscribeKey(): Boolean = settings.currentKey.isNotBlank()

    private fun openSettings() {
        startActivity(
            Intent(this, SettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun switchAway() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            switchToPreviousInputMethod()
        } else {
            @Suppress("DEPRECATION")
            (getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
                ?.showInputMethodPicker()
        }
    }

    private fun haptic() {
        val effect = VibrationEffect.createOneShot(18, VibrationEffect.DEFAULT_AMPLITUDE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vm?.defaultVibrator?.vibrate(effect)
        } else {
            @Suppress("DEPRECATION")
            (getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)?.vibrate(effect)
        }
    }
}
