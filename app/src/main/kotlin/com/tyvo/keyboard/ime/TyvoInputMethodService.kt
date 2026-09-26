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
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.core.content.ContextCompat
import com.tyvo.keyboard.actions.QuickAction
import com.tyvo.keyboard.audio.AudioRecorder
import com.tyvo.keyboard.data.Settings
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

    private lateinit var settings: Settings
    private lateinit var recorder: AudioRecorder
    private lateinit var transcriber: Transcriber
    private lateinit var polisher: Polisher

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
        transcriber = Transcriber(settings)
        polisher = Polisher(settings)
    }

    override fun onCreateInputView(): View {
        val v = KeyboardView(this)
        v.onMicPressed = { toggleRecording() }
        v.onAction = { runAction(it) }
        v.onUndo = { undo() }
        v.onAccept = { acceptSession() }
        v.onRetryPolish = { retryPolish() }
        v.onOpenSettings = { openSettings() }
        v.onSwitchKeyboard = { switchAway() }
        v.onBackspace = { backspace() }
        v.onNewline = { commitNewline() }
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
        val file = recorder.stop()
        haptic()
        if (file == null) {
            render(stateForIdle().let {
                UiState.Idle("Too short - hold the mic while speaking.")
            })
            return
        }
        process(file)
    }

    private fun abortRecording() {
        ticker?.cancel()
        if (recorder.isRecording) recorder.cancel()
    }

    // ---- Pipeline -------------------------------------------------------

    private fun process(audio: File) {
        pipeline?.cancel()
        pipeline = scope.launch {
            try {
                render(UiState.Working("Transcribing"))
                val raw = transcriber.transcribe(audio)
                if (raw.isBlank()) {
                    render(UiState.Idle("Nothing heard."))
                    return@launch
                }

                val shouldPolish = settings.autoPolish &&
                    settings.polishProvider != com.tyvo.keyboard.data.PolishProvider.NONE

                if (!shouldPolish) {
                    commitFresh(raw)
                    render(UiState.Review(raw, session.canUndo))
                    return@launch
                }

                // Commit the raw text first so it appears immediately, then
                // swap in the polished version. Waiting for the LLM before
                // showing anything makes the keyboard feel broken.
                commitFresh(raw)
                render(UiState.Working("Polishing"))
                val polished = try {
                    polisher.cleanUp(raw)
                } catch (e: TyvoException) {
                    render(UiState.Review(raw, session.canUndo, note = e.message))
                    return@launch
                }
                if (polished != raw) {
                    replaceCommitted(polished)
                    session.advance(polished)
                }
                render(UiState.Review(session.current, session.canUndo))
            } catch (e: TyvoException) {
                render(UiState.Idle(e.message))
            } catch (e: Exception) {
                render(UiState.Idle("Something went wrong."))
            } finally {
                audio.delete()
            }
        }
    }

    private fun runAction(action: QuickAction) = runInstruction(action.instruction, action.label)

    private fun runCustom(instruction: String) = runInstruction(instruction, "Rewriting")

    private fun runInstruction(instruction: String, label: String) {
        val text = session.current
        if (text.isBlank()) return
        pipeline?.cancel()
        pipeline = scope.launch {
            render(UiState.Review(text, session.canUndo, busy = true, note = label))
            try {
                val result = polisher.transform(text, instruction)
                if (result != text) {
                    replaceCommitted(result)
                    session.advance(result)
                }
                render(UiState.Review(session.current, session.canUndo))
            } catch (e: TyvoException) {
                render(UiState.Review(text, session.canUndo, note = e.message))
            } catch (e: Exception) {
                render(UiState.Review(text, session.canUndo, note = "Rewrite failed."))
            }
        }
    }

    private fun retryPolish() {
        val text = session.current
        if (text.isBlank()) return
        pipeline?.cancel()
        pipeline = scope.launch {
            render(UiState.Review(text, session.canUndo, busy = true, note = "Polishing"))
            try {
                val polished = polisher.cleanUp(text)
                if (polished != text) {
                    replaceCommitted(polished)
                    session.advance(polished)
                }
                render(UiState.Review(session.current, session.canUndo))
            } catch (e: TyvoException) {
                render(UiState.Review(text, session.canUndo, note = e.message))
            }
        }
    }

    private fun undo() {
        val prev = session.undo() ?: return
        replaceCommitted(prev)
        haptic()
        render(UiState.Review(prev, session.canUndo))
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
    private fun commitFresh(text: String) {
        val ic = currentInputConnection ?: return
        val spaced = if (needsLeadingSpace(ic)) " $text" else text
        ic.beginBatchEdit()
        ic.commitText(spaced, 1)
        ic.endBatchEdit()
        committed = spaced
        session.begin(spaced)
    }

    /**
     * Swaps our previously committed text for [text].
     *
     * Deletes exactly as many characters as we put in, so text the user typed
     * around ours is untouched. If the field no longer ends with our text --
     * the user edited it meanwhile -- we append instead of destroying their
     * edit.
     */
    private fun replaceCommitted(text: String) {
        val ic = currentInputConnection ?: return
        val prior = committed
        val leading = prior.takeWhile { it == ' ' }
        val replacement = leading + text.trimStart()

        ic.beginBatchEdit()
        val before = ic.getTextBeforeCursor(prior.length, 0)?.toString()
        if (before == prior) {
            ic.deleteSurroundingText(prior.length, 0)
            ic.commitText(replacement, 1)
            committed = replacement
        } else {
            // Field diverged from what we wrote; don't clobber the user.
            ic.commitText(text, 1)
            committed = text
        }
        ic.endBatchEdit()
    }

    private fun needsLeadingSpace(ic: InputConnection): Boolean {
        val before = ic.getTextBeforeCursor(1, 0)?.toString().orEmpty()
        return before.isNotEmpty() && before.last().isLetterOrDigit() ||
            before.isNotEmpty() && before.last() in ".,!?;:\""
    }

    private fun backspace() {
        val ic = currentInputConnection ?: return
        // Typing invalidates the session: our committed text no longer matches.
        session.clear()
        committed = ""
        ic.deleteSurroundingText(1, 0)
        render(UiState.Idle())
    }

    private fun commitNewline() {
        val ic = currentInputConnection ?: return
        ic.commitText("\n", 1)
        session.clear()
        committed = ""
        render(UiState.Idle())
    }

    // ---- Misc -----------------------------------------------------------

    private fun stateForIdle(): UiState =
        if (session.isActive) UiState.Review(session.current, session.canUndo)
        else UiState.Idle()

    private fun render(state: UiState) {
        view?.render(state)
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasTranscribeKey(): Boolean =
        settings.keyFor(settings.transcribeProvider).isNotBlank()

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
