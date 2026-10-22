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
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodSubtype
import androidx.core.content.ContextCompat
import com.tyvo.keyboard.actions.QuickAction
import com.tyvo.keyboard.audio.AudioRecorder
import com.tyvo.keyboard.bubble.isBubbleEnabled
import android.content.res.Configuration
import androidx.core.view.WindowInsetsControllerCompat
import com.tyvo.keyboard.data.ContextBehavior
import com.tyvo.keyboard.data.InputContext
import com.tyvo.keyboard.data.Settings
import com.tyvo.keyboard.history.Maintenance
import com.tyvo.keyboard.history.RecordingStore
import com.tyvo.keyboard.usage.UsageStore
import com.tyvo.keyboard.net.TyvoException
import com.tyvo.keyboard.polish.Polisher
import com.tyvo.keyboard.settings.SettingsActivity
import com.tyvo.keyboard.transcribe.Transcriber
import com.tyvo.keyboard.ui.KeyboardColors
import com.tyvo.keyboard.ui.KeyboardView
import com.tyvo.keyboard.ui.PadKind
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
        /** How far back to look when deleting a word. */
        const val WORD_LOOKBEHIND = 96

        /** The imeSubtypeMode of the voice subtype (mic-key handoff). */
        const val SUBTYPE_MODE_VOICE = "voice"
    }

    private lateinit var settings: Settings
    private lateinit var recorder: AudioRecorder
    private lateinit var transcriber: Transcriber
    private lateinit var polisher: Polisher
    private lateinit var store: RecordingStore
    private lateinit var usage: UsageStore
    private lateinit var dictation: Dictation

    /** Most recent failed recording, retryable straight from the keyboard. */
    private var lastFailedId: String? = null

    private val session = DictationSession()
    private var view: KeyboardView? = null

    /**
     * True when the active subtype is the voice one: another keyboard
     * launched us from its mic key, so the UI is compact and the keyboard
     * returns to that keyboard once text lands or the user cancels.
     */
    private var voiceMode = false

    private var pipeline: Job? = null

    /**
     * A finished dictation with nowhere to go yet: the phone locked, or the
     * field closed, before transcription came back. Inserted as soon as a
     * field shows the keyboard again. It is in History regardless.
     */
    private var pendingInsert: Dictation.Result.Text? = null

    private var screenOff: android.content.BroadcastReceiver? = null
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
        dictation = Dictation(this, settings, store, transcriber, polisher)
        Maintenance.runInBackground(store, settings)
        // Locking mid-sentence ends the dictation as if Stop was tapped,
        // rather than as if it never happened.
        screenOff = onScreenOff { stopAndProcess() }
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
        v.onBackspaceHold = { deleteStaged() }
        v.onNewline = { commitLiteral("\n") }
        v.onSpace = { commitLiteral(" ") }
        v.onEnableBubble = { openBubbleSetup() }
        v.onPadKey = { commitLiteral(it) }
        v.onEnter = { performEnter() }
        v.onCancel = { cancelVoiceMode() }
        view = v
        render(UiState.Idle())
        return v
    }

    /**
     * Tells the system whether to draw its own keyboard-row icons -- the
     * globe and the dismiss chevron below our controls -- dark or light.
     *
     * Android cannot infer this from our surface, so it defaults to light
     * icons, which wash out against the light theme. This is the only handle
     * we have on that row: the icons themselves belong to the system.
     */
    private fun applyNavigationBarAppearance() {
        val night = resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val w = window?.window ?: return
        val decor = w.decorView

        // Match the keyboard's own background so the row reads as part of it
        // rather than a band beneath it.
        @Suppress("DEPRECATION")
        w.navigationBarColor = if (night) KeyboardColors.BG_DARK else KeyboardColors.BG_LIGHT

        // "Light navigation bars" describes the *background*, so the system
        // draws its icons dark -- which is what the light theme needs.
        WindowInsetsControllerCompat(w, decor).isAppearanceLightNavigationBars = !night

        // The compat controller does not always take on an IME window, so set
        // the legacy flag too. They agree, so whichever wins is correct.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            @Suppress("DEPRECATION")
            decor.systemUiVisibility =
                if (night) {
                    decor.systemUiVisibility and
                        View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
                } else {
                    decor.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                }
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // A new field means the previous session's text is no longer ours.
        if (!restarting) {
            session.clear()
            committed = ""
        }
        val yielded = applyContext(info)
        val pending = pendingInsert
        if (!yielded && pending != null && !isLocked()) {
            pendingInsert = null
            commitFresh(pending.text, transcript = pending.raw)
            // Voice subtype mode is dictate-and-return.
            if (voiceMode) switchAway() else render(reviewOrIdle(note = pending.note))
        } else if (pipeline?.isActive != true) {
            // Mid-transcription the status line is still telling the truth.
            render(idleStateForMode())
        }
    }

    /**
     * Adapts the keyboard to the field it is in front of.
     *
     * Returns true when the field is handed straight back to the previous
     * keyboard (ContextBehavior.YIELD): there is nothing left to show or
     * commit here.
     */
    private fun applyContext(info: EditorInfo?): Boolean {
        voiceMode = isVoiceSubtype()
        val v = view ?: return false
        if (voiceMode) {
            // Launched from another keyboard's mic key: dictate and return,
            // whatever the field.
            v.compact = true
            v.pad = null
            return false
        }
        v.compact = false
        val context = info?.let { InputContext.of(it.inputType, it.imeOptions) }
            ?: InputContext.TEXT
        return when (InputContext.behaviorFor(
            context,
            numeric = settings.numericBehavior,
            search = settings.searchBehavior,
            password = settings.passwordBehavior,
            uri = settings.uriBehavior,
        )) {
            ContextBehavior.VOICE -> {
                v.pad = null
                false
            }
            ContextBehavior.FALLBACK -> {
                v.pad = if (context == InputContext.NUMERIC) PadKind.NUMERIC else PadKind.TEXT
                false
            }
            ContextBehavior.YIELD -> {
                v.pad = null
                switchAway()
                true
            }
        }
    }

    /** True when the active subtype is the voice one (mic-key handoff). */
    private fun isVoiceSubtype(): Boolean {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        return imm?.currentInputMethodSubtype?.mode == SUBTYPE_MODE_VOICE
    }

    override fun onCurrentInputMethodSubtypeChanged(subtype: InputMethodSubtype) {
        super.onCurrentInputMethodSubtypeChanged(subtype)
        applyContext(currentInputEditorInfo)
        if (!session.isActive && pipeline?.isActive != true) {
            render(idleStateForMode())
        }
    }

    /**
     * The idle state for the current UI mode. A fallback pad types without
     * an API key, so an unconfigured keyboard still gets the pad rather
     * than the setup prompt.
     */
    private fun idleStateForMode(): UiState =
        if (view?.pad != null) UiState.Idle() else stateForIdle()

    /**
     * Re-checks setup whenever the keyboard comes back up.
     *
     * Tapping "Set up Tyvo" opens the app over the keyboard, and coming back
     * does not always restart input on the same field -- so without this, a
     * user who just pasted their key would return to the setup button still
     * telling them to paste their key.
     */
    override fun onWindowShown() {
        super.onWindowShown()
        // Every time the window appears, not once at creation: the window is
        // not attached yet in onCreateInputView, and the theme can change
        // between one showing and the next.
        applyNavigationBarAppearance()
        // Only while idle: re-rendering mid-session would throw away a review
        // the user is still working with.
        if (!session.isActive && pipeline?.isActive != true) {
            render(idleStateForMode())
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        // The keyboard closing -- including because the phone locked -- is
        // not the user throwing the recording away. Finish it properly.
        stopAndProcess()
    }

    override fun onDestroy() {
        screenOff?.let { runCatching { unregisterReceiver(it) } }
        abortRecording()
        scope.cancel()
        super.onDestroy()
    }

    // ---- Recording ------------------------------------------------------

    private fun toggleRecording() {
        if (recorder.isRecording) stopAndProcess() else startRecording()
    }

    private fun startRecording() {
        // Should be unreachable -- an unconfigured keyboard shows the setup
        // button instead of the mic -- but a stale view must not start a
        // recording that cannot go anywhere.
        setupNeeded()?.let {
            render(UiState.NeedsSetup(it))
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
        val (id, file) = dictation.save(audio, durationMs)
        transcribeRecording(id, file, durationMs)
    }

    /**
     * Runs transcription plus the polish pass for one stored recording.
     * [durationMs] is set only for a fresh dictation; see [Dictation.run].
     */
    private fun transcribeRecording(recordingId: String?, audio: File, durationMs: Long? = null) {
        pipeline?.cancel()
        pipeline = scope.launch {
            val result = dictation.run(recordingId, audio, durationMs) {
                render(UiState.Working(it))
            }
            when (result) {
                is Dictation.Result.Text -> {
                    lastFailedId = null
                    if (currentInputConnection == null || isLocked()) {
                        pendingInsert = result
                        render(UiState.Idle("Saved. It goes in when you're back in a field."))
                    } else {
                        commitFresh(result.text, transcript = result.raw)
                        // Voice subtype mode is dictate-and-return: the text
                        // lands, the keyboard goes back to the one that
                        // launched it.
                        if (voiceMode) switchAway() else render(reviewOrIdle(note = result.note))
                    }
                }
                Dictation.Result.NothingHeard -> {
                    lastFailedId = recordingId
                    render(UiState.Idle("Nothing heard. Saved to Tyvo.", canRetry = true))
                }
                // A mistaken tap: back to where they were, with no message.
                Dictation.Result.Discarded -> {
                    lastFailedId = null
                    render(reviewOrIdle())
                }
                is Dictation.Result.Failed -> {
                    // The keyboard offers an immediate retry, and the
                    // notification points at the app for later -- the
                    // recording is safe either way.
                    lastFailedId = recordingId
                    render(
                        UiState.Idle(
                            lastError = if (recordingId != null) {
                                "${result.message} Saved - tap Retry."
                            } else {
                                result.message
                            },
                            canRetry = recordingId != null,
                        )
                    )
                }
            }
        }
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

    private fun runAction(action: QuickAction) =
        runInstruction(action.instruction, action.label, action.id, action.translating)

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

    /**
     * Enter on a fallback pad: the field's own action (Search, Go, Done…)
     * rather than a raw newline, so the pad behaves like a real keyboard's.
     */
    private fun performEnter() {
        if (!sendDefaultEditorAction(true)) commitLiteral("\n")
    }

    /**
     * Voice subtype mode, dismissed without dictating: drop anything in
     * flight and go back to the keyboard that launched us.
     */
    private fun cancelVoiceMode() {
        pipeline?.cancel()
        abortRecording()
        session.clear()
        switchAway()
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
        return joinsWithSpace(prev)
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
     * Deletes the dictated text still under review, all of it, leading space
     * included. Returns false without touching the field if the text before
     * the cursor is no longer exactly ours -- the user has edited it, so it is
     * theirs now, and the held key goes back to deleting normally.
     */
    private fun deleteStaged(): Boolean {
        val ic = currentInputConnection ?: return false
        val prior = committed
        if (prior.isEmpty() || !session.isActive) return false

        ic.beginBatchEdit()
        val matched = ic.getTextBeforeCursor(prior.length, 0)?.toString() == prior
        if (matched) ic.deleteSurroundingText(prior.length, 0)
        ic.endBatchEdit()

        session.clear()
        committed = ""
        if (matched) haptic()
        render(UiState.Idle(canRetry = lastFailedId != null))
        return matched
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

    private fun stateForIdle(): UiState = when {
        setupNeeded() != null -> UiState.NeedsSetup(setupNeeded()!!)
        session.isActive -> reviewOrIdle()
        else -> UiState.Idle(canRetry = lastFailedId != null)
    }

    /**
     * What is stopping the keyboard from working, or null if nothing is.
     *
     * Checked every time the keyboard is shown rather than once at startup:
     * the user can grant permission or paste a key while the keyboard is
     * alive, and it should notice on the way back.
     */
    private fun setupNeeded(): String? = when {
        !hasTranscribeKey() -> "Tyvo needs an API key before it can hear you."
        !hasMicPermission() -> "Tyvo needs permission to use the microphone."
        else -> null
    }

    private fun render(state: UiState) {
        view?.let {
            // Only the idle strip shows the chip, and recording re-renders
            // every frame: no need to read system settings each time.
            if (state is UiState.Idle || state is UiState.NeedsSetup) {
                it.offerBubble = !isBubbleEnabled(this)
            }
            it.render(state)
        }
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

    /** Into the app, at the explanation that comes before the switch. */
    private fun openBubbleSetup() {
        startActivity(
            Intent(this, SettingsActivity::class.java)
                .setAction(SettingsActivity.ACTION_ENABLE_BUBBLE)
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
