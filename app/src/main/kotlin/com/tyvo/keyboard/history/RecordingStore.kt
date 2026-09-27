package com.tyvo.keyboard.history

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
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
     * Everything, newest first, with unfinished recordings pinned to the top
     * so work that still needs attention cannot scroll out of sight.
     */
    fun all(): List<Recording> = read().sortedWith(
        compareByDescending<Recording> { it.needsAttention }
            .thenByDescending { it.createdAt }
    )

    fun needingAttention(): List<Recording> = all().filter { it.needsAttention }

    /** Bytes held by retained audio, for showing storage use. */
    fun audioBytes(): Long =
        audioDir.listFiles()?.sumOf { it.length() } ?: 0L

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

    private fun write(items: List<Recording>) {
        val arr = JSONArray()
        items.forEach { arr.put(toJson(it)) }
        val tmp = File(root, "index.json.tmp")
        try {
            tmp.writeText(arr.toString())
            if (!tmp.renameTo(index)) {
                index.writeText(arr.toString())
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
