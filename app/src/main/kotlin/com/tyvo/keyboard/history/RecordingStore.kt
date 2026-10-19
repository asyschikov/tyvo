package com.tyvo.keyboard.history

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Durable store of every dictation.
 *
 * Design rule: audio lands on disk and is indexed *before* the network is
 * touched, so losing connectivity, crashing, or being killed by the system
 * mid-request can never destroy what the user said. Audio is only deleted
 * once text exists to replace it.
 *
 * The index is a single JSON file rewritten atomically. A database would be
 * better with thousands of rows, but this is read once per screen and written
 * once per dictation, and a corrupt index here means losing the user's
 * recordings -- so the simplest thing that can be written atomically wins.
 */
class RecordingStore(baseDir: File) {

    /** The only thing this needs from a Context is a directory to own. */
    constructor(context: Context) : this(context.filesDir)

    private val root = File(baseDir, "recordings").apply { mkdirs() }
    private val audioDir = File(root, "audio").apply { mkdirs() }
    private val index = File(root, "index.json")
    private val lock = Any()

    /** Where a new recording's audio should be written. */
    fun newAudioFile(): File = File(audioDir, "rec_${System.currentTimeMillis()}.wav")

    fun audioFor(r: Recording): File? =
        r.audioFile?.let { File(audioDir, it) }?.takeIf { it.exists() }

    /**
     * Records a freshly captured dictation, before transcription is attempted.
     *
     * [audio] is moved into the store, so the caller must not delete it.
     */
    fun addPending(audio: File, durationMs: Long): Recording = synchronized(lock) {
        val name = if (audio.parentFile == audioDir) audio.name else {
            val dest = File(audioDir, "rec_${System.currentTimeMillis()}.wav")
            audio.copyTo(dest, overwrite = true)
            audio.delete()
            dest.name
        }
        val rec = Recording(
            id = UUID.randomUUID().toString(),
            createdAt = System.currentTimeMillis(),
            status = Recording.Status.PENDING,
            audioFile = name,
            durationMs = durationMs,
        )
        write(read() + rec)
        rec
    }

    /** Marks a recording transcribed and drops its audio. */
    fun markDone(id: String, text: String) = synchronized(lock) {
        val all = read()
        val rec = all.firstOrNull { it.id == id } ?: return@synchronized
        // Only now is it safe to delete the audio: the text has replaced it.
        rec.audioFile?.let { File(audioDir, it).delete() }
        write(
            all.map {
                if (it.id == id) it.copy(
                    status = Recording.Status.DONE,
                    text = text,
                    audioFile = null,
                    error = null,
                ) else it
            }
        )
    }

    /** Records a failed attempt, keeping the audio for a retry. */
    fun markFailed(id: String, error: String) = synchronized(lock) {
        write(
            read().map {
                if (it.id == id) it.copy(
                    status = Recording.Status.FAILED,
                    error = error,
                    attempts = it.attempts + 1,
                ) else it
            }
        )
    }

    /** Updates the stored text of an already-transcribed recording. */
    fun updateText(id: String, text: String) = synchronized(lock) {
        write(read().map { if (it.id == id) it.copy(text = text) else it })
    }

    fun delete(id: String) = synchronized(lock) {
        val all = read()
        all.firstOrNull { it.id == id }?.audioFile?.let { File(audioDir, it).delete() }
        write(all.filterNot { it.id == id })
    }

    fun deleteAll() = synchronized(lock) {
        audioDir.listFiles()?.forEach { it.delete() }
        write(emptyList())
    }

    fun get(id: String): Recording? = read().firstOrNull { it.id == id }

    /**
     * Re-adopts audio files the index has lost track of.
     *
     * This is what makes the index genuinely disposable: even if it is
     * corrupted or deleted outright, the recordings themselves reappear as
     * PENDING and can still be retried. Without it a damaged index would
     * leave WAVs on disk that the app could never show.
     *
     * Returns how many were recovered.
     */
    fun recoverOrphanedAudio(): Int = synchronized(lock) {
        val known = read()
        val referenced = known.mapNotNull { it.audioFile }.toSet()
        val orphans = audioDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".wav") && it.name !in referenced }
            ?: return@synchronized 0
        if (orphans.isEmpty()) return@synchronized 0

        val recovered = orphans.map { file ->
            Recording(
                id = UUID.randomUUID().toString(),
                createdAt = file.lastModified(),
                status = Recording.Status.PENDING,
                audioFile = file.name,
                durationMs = durationOfWav(file),
                error = "Recovered after an interrupted session.",
            )
        }
        write(known + recovered)
        recovered.size
    }

    /** Duration from the WAV header, for a recovered file with no metadata. */
    private fun durationOfWav(file: File): Long {
        val bytes = file.length() - 44
        if (bytes <= 0) return 0
        // 16 kHz, mono, 16-bit -- what the recorder always writes.
        return bytes * 1000 / (16_000 * 2)
    }

    /**
     * Everything, newest first, with unfinished recordings pinned to the top
     * so work that still needs attention cannot scroll out of sight.
     */
    fun all(): List<Recording> = read().sortedWith(
        compareByDescending<Recording> { it.needsAttention }
            .thenByDescending { it.createdAt }
    )

    fun needingAttention(): List<Recording> = all().filter { it.needsAttention }

    /**
     * Expires audio older than [maxAgeMs], keeping every transcript.
     *
     * Only the WAV is removed -- the row and its text stay forever, because
     * text costs almost nothing and is the part the user came for. An expired
     * recording simply stops being retryable.
     *
     * [keepFailed] exempts failed and pending recordings, which is the case
     * where the audio is the only copy of what was said.
     *
     * Returns how many files were freed.
     */
    fun expireAudio(
        maxAgeMs: Long = DEFAULT_MAX_AUDIO_AGE_MS,
        keepFailed: Boolean = false,
        now: Long = System.currentTimeMillis(),
    ): Int = synchronized(lock) {
        val all = read()
        var freed = 0
        val updated = all.map { rec ->
            val expired = rec.audioFile != null &&
                now - rec.createdAt > maxAgeMs &&
                !(keepFailed && rec.needsAttention)
            if (!expired) rec else {
                File(audioDir, rec.audioFile!!).delete()
                freed++
                rec.copy(
                    audioFile = null,
                    // Say why it can no longer be retried, rather than
                    // silently dropping the button.
                    error = if (rec.needsAttention) {
                        "${rec.error ?: "Failed."} Audio expired."
                    } else rec.error,
                )
            }
        }
        if (freed > 0) write(updated)
        freed
    }

    /** Bytes held by retained audio, for showing storage use. */
    fun audioBytes(): Long =
        audioDir.listFiles()?.sumOf { it.length() } ?: 0L

    companion object {
        /** Audio older than this is dropped; transcripts are never expired. */
        const val DEFAULT_MAX_AUDIO_AGE_MS = 24 * 60 * 60 * 1000L
    }

    // ---- persistence -----------------------------------------------------

    private fun read(): List<Recording> {
        if (!index.exists()) return emptyList()
        return try {
            val arr = JSONArray(index.readText())
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let(::fromJson)
            }
        } catch (e: Exception) {
            // A damaged index must not take the audio with it: the files stay
            // on disk and can be recovered by hand.
            emptyList()
        }
    }

    /**
     * Rewrites the index atomically and durably.
     *
     * The temp file is flushed to disk with fsync *before* the rename:
     * renaming is atomic for readers, but without the sync a power loss can
     * leave the renamed file full of zeroes, which is a documented ext4
     * behaviour rather than a theoretical one.
     *
     * There is deliberately no fallback that writes the index in place. That
     * is exactly the non-atomic operation the temp file exists to avoid, and
     * a failed write that leaves the previous index intact is much better
     * than one that truncates it.
     */
    private fun write(items: List<Recording>) {
        val arr = JSONArray()
        items.forEach { arr.put(toJson(it)) }
        val tmp = File(root, "index.json.tmp")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(arr.toString().toByteArray())
                out.flush()
                out.fd.sync()
            }
            if (!tmp.renameTo(index)) {
                // Leave the old index in place; the audio is still on disk and
                // recovery will pick it up on the next load.
                tmp.delete()
            }
        } catch (e: Exception) {
            tmp.delete()
        }
    }

    private fun toJson(r: Recording) = JSONObject().apply {
        put("id", r.id)
        put("createdAt", r.createdAt)
        put("status", r.status.name)
        r.text?.let { put("text", it) }
        r.audioFile?.let { put("audioFile", it) }
        put("durationMs", r.durationMs)
        r.error?.let { put("error", it) }
        put("attempts", r.attempts)
    }

    private fun fromJson(o: JSONObject): Recording? {
        val id = o.optString("id").takeIf { it.isNotBlank() } ?: return null
        val status = runCatching {
            Recording.Status.valueOf(o.optString("status"))
        }.getOrNull() ?: return null
        return Recording(
            id = id,
            createdAt = o.optLong("createdAt"),
            status = status,
            text = o.optString("text").takeIf { it.isNotBlank() },
            audioFile = o.optString("audioFile").takeIf { it.isNotBlank() },
            durationMs = o.optLong("durationMs"),
            error = o.optString("error").takeIf { it.isNotBlank() },
            attempts = o.optInt("attempts"),
        )
    }
}
