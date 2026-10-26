package com.tyvo.keyboard.ime

import android.content.Context
import android.widget.Toast
import com.tyvo.keyboard.data.Settings
import com.tyvo.keyboard.history.Notifications
import com.tyvo.keyboard.history.RecordingStore
import com.tyvo.keyboard.net.TyvoException
import com.tyvo.keyboard.polish.Polisher
import com.tyvo.keyboard.transcribe.Transcriber
import java.io.File

/**
 * Turns a finished recording into the text that should go in the field.
 *
 * Shared by the keyboard and the floating mic, so both keep the same promise:
 * the audio is stored before the network is touched, and a failure leaves a
 * retryable recording behind rather than losing what was said. The caller
 * only decides where the text goes.
 */
class Dictation(
    private val context: Context,
    private val settings: Settings,
    private val store: RecordingStore,
    private val transcriber: Transcriber,
    private val polisher: Polisher,
) {

    sealed interface Result {
        /**
         * [text] goes in the field; [raw] is what was actually said. [note]
         * explains a clean-up that was attempted and failed, in which case
         * [text] is the raw transcript.
         */
        data class Text(val text: String, val raw: String, val note: String? = null) : Result

        /** Transcribed fine, but there were no words. The audio is kept. */
        data object NothingHeard : Result

        /**
         * A short clip with no words, deleted without a trace. The caller
         * should say nothing at all: there was nothing to lose.
         */
        data object Discarded : Result

        data class Failed(val message: String) : Result
    }

    /**
     * Hands a recording to the store, returning its id (null if indexing
     * failed) and the audio file to transcribe from.
     *
     * Even if indexing fails, the caller should still transcribe rather than
     * drop the dictation on the floor.
     */
    fun save(audio: File, durationMs: Long): Pair<String?, File> {
        val recording = try {
            store.addPending(audio, durationMs)
        } catch (e: Exception) {
            null
        }
        return recording?.id to (recording?.let { store.audioFor(it) } ?: audio)
    }

    /**
     * Transcribes, then polishes if enabled. [onStep] receives the stage
     * name ("Transcribing", "Cleaning up") as each one begins.
     *
     * [durationMs] is passed only for a fresh dictation. A retry of a saved
     * recording leaves it null, so a recording the user went back to on
     * purpose is never discarded.
     */
    suspend fun run(
        recordingId: String?,
        audio: File,
        durationMs: Long? = null,
        onStep: (String) -> Unit,
    ): Result {
        return try {
            onStep("Transcribing")
            val raw = transcriber.transcribe(audio)
            if (raw.isBlank() && shouldDiscard(durationMs, settings.discardShortSilence)) {
                if (recordingId != null) store.delete(recordingId) else audio.delete()
                return Result.Discarded
            }
            if (raw.isBlank()) {
                // Nothing heard is a real outcome, not a failure: keep the
                // audio so the user can check for themselves.
                recordingId?.let { store.markFailed(it, "No speech detected.") }
                notifyFailure("No speech detected.")
                return Result.NothingHeard
            }

            recordingId?.let { store.markDone(it, raw) }

            if (!(settings.autoPolish && settings.polishEnabled)) {
                return Result.Text(raw, raw)
            }

            // Nothing is inserted until the text is final. Committing the
            // raw transcript first and swapping it a second later puts
            // visibly wrong words in someone's message and rewrites them
            // under the cursor, which reads as a glitch even though it is
            // faster.
            onStep("Cleaning up")
            val polished = try {
                polisher.cleanUp(raw)
            } catch (e: TyvoException) {
                // Clean-up failed, but the words are good: insert them
                // rather than making the user dictate again, and say so
                // everywhere the text shows up, History included.
                val why = "Clean-up failed: ${e.message ?: "unknown error"}"
                recordingId?.let { store.markCleanupFailed(it, why) }
                return Result.Text(raw, raw, note = why)
            }
            if (polished != raw) {
                recordingId?.let { store.updateText(it, polished) }
            }
            Result.Text(polished, raw)
        } catch (e: TyvoException) {
            fail(recordingId, e.message ?: "Transcription failed.")
        } catch (e: Exception) {
            fail(recordingId, "Transcription failed.")
        }
    }

    /**
     * Records a failed transcription without losing the audio. Only a stored
     * recording earns a notification: without one there is nothing in the
     * app to point at.
     */
    private fun fail(recordingId: String?, message: String): Result {
        recordingId?.let { store.markFailed(it, message) }
        if (recordingId != null) notifyFailure(message)
        return Result.Failed(message)
    }

    /**
     * Tells the user a dictation was saved rather than lost.
     *
     * Two channels because they cover different moments: the toast is seen
     * now, while they are still looking at the field, and the notification is
     * found later, once they have wandered off. Both are optional, and the
     * caller's own status carries the message regardless.
     */
    companion object {
        /** Below this, a clip with no words is a mistake rather than a dictation. */
        const val SHORT_SILENCE_MS = 3_000L

        fun shouldDiscard(durationMs: Long?, enabled: Boolean): Boolean =
            enabled && durationMs != null && durationMs < SHORT_SILENCE_MS
    }

    private fun notifyFailure(message: String) {
        if (settings.failureToasts) {
            runCatching {
                Toast.makeText(context, "$message Saved to Tyvo.", Toast.LENGTH_LONG).show()
            }
        }
        if (settings.failureNotifications) {
            Notifications.transcriptionFailed(context, store.needingAttention().size)
        }
    }
}
