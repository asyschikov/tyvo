package com.tyvo.keyboard.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Records 16 kHz mono PCM straight to a WAV file.
 *
 * WAV rather than a compressed container because both transcription APIs
 * accept it and it lets us stream bytes to disk as they arrive, so stopping
 * the recording costs nothing -- the file is already written and we only have
 * to backfill the RIFF header with the final sizes.
 *
 * Amplitude is published on the fly so the keyboard can draw a live waveform.
 */
class AudioRecorder(private val outputDir: File) {

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val CHANNELS = 1
        private const val BITS_PER_SAMPLE = 16
        private const val HEADER_BYTES = 44
        /** Hard stop so a forgotten recording can't fill the disk or blow API limits. */
        const val MAX_DURATION_MS = 5 * 60 * 1000L
    }

    @Volatile
    private var recording = false
    private var recorder: AudioRecord? = null
    private var worker: Thread? = null
    private var target: File? = null

    /** Latest RMS amplitude, 0f..1f, for waveform rendering. */
    @Volatile
    var amplitude: Float = 0f
        private set

    @Volatile
    var startedAtMs: Long = 0L
        private set

    val isRecording: Boolean get() = recording

    /**
     * Begins capture. Returns the file being written, or null if the mic
     * could not be opened (permission revoked, or in use by another app).
     */
    @SuppressLint("MissingPermission")
    fun start(onAutoStop: () -> Unit = {}): File? {
        if (recording) return target

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) return null
        val bufSize = minBuf * 2

        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize,
            )
        } catch (e: SecurityException) {
            return null
        } catch (e: IllegalArgumentException) {
            return null
        }

        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return null
        }

        val file = File(outputDir, "tyvo_${System.currentTimeMillis()}.wav")
        val raf = try {
            RandomAccessFile(file, "rw").apply {
                setLength(0)
                write(ByteArray(HEADER_BYTES)) // placeholder, backfilled on stop
            }
        } catch (e: Exception) {
            rec.release()
            return null
        }

        recorder = rec
        target = file
        recording = true
        startedAtMs = System.currentTimeMillis()

        try {
            rec.startRecording()
        } catch (e: IllegalStateException) {
            recording = false
            rec.release()
            raf.close()
            return null
        }

        worker = thread(name = "tyvo-rec", isDaemon = true) {
            val buf = ByteArray(bufSize)
            var total = 0L
            var hitLimit = false
            try {
                while (recording) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    raf.write(buf, 0, n)
                    total += n
                    amplitude = rms(buf, n)
                    if (System.currentTimeMillis() - startedAtMs > MAX_DURATION_MS) {
                        hitLimit = true
                        recording = false
                    }
                }
            } catch (e: Exception) {
                // fall through: whatever was captured stays valid once the
                // header is fixed up below
            } finally {
                runCatching { writeWavHeader(raf, total) }
                runCatching { raf.close() }
                amplitude = 0f
            }
            if (hitLimit) onAutoStop()
        }
        return file
    }

    /**
     * Stops capture and returns the finished WAV, or null if nothing usable
     * was recorded (too short to transcribe).
     */
    fun stop(): File? {
        if (!recording) return null
        recording = false
        runCatching { worker?.join(1500) }
        worker = null
        runCatching {
            recorder?.stop()
        }
        runCatching { recorder?.release() }
        recorder = null
        amplitude = 0f

        val file = target
        target = null
        if (file == null || !file.exists()) return null
        // Under ~0.25s of audio is almost always an accidental tap.
        val payload = file.length() - HEADER_BYTES
        val minBytes = SAMPLE_RATE * (BITS_PER_SAMPLE / 8) / 4
        if (payload < minBytes) {
            file.delete()
            return null
        }
        return file
    }

    /** Aborts capture and deletes the partial file. */
    fun cancel() {
        val f = target
        stop()
        f?.delete()
    }

    private fun rms(buf: ByteArray, n: Int): Float {
        var sum = 0.0
        var i = 0
        val count = n / 2
        if (count == 0) return 0f
        while (i + 1 < n) {
            val s = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort()
            val v = s.toDouble() / Short.MAX_VALUE
            sum += v * v
            i += 2
        }
        // Scale up: speech RMS sits low, and the bar should read as "live".
        return min(1.0, sqrt(sum / count) * 3.5).toFloat()
    }

    private fun writeWavHeader(raf: RandomAccessFile, dataBytes: Long) {
        val byteRate = SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8
        val blockAlign = CHANNELS * BITS_PER_SAMPLE / 8
        val out = ByteArrayOutputStream(HEADER_BYTES)

        fun ascii(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        fun le32(v: Long) {
            out.write((v and 0xFF).toInt())
            out.write((v shr 8 and 0xFF).toInt())
            out.write((v shr 16 and 0xFF).toInt())
            out.write((v shr 24 and 0xFF).toInt())
        }
        fun le16(v: Int) {
            out.write(v and 0xFF)
            out.write(v shr 8 and 0xFF)
        }

        ascii("RIFF"); le32(36 + dataBytes); ascii("WAVE")
        ascii("fmt "); le32(16); le16(1); le16(CHANNELS)
        le32(SAMPLE_RATE.toLong()); le32(byteRate.toLong())
        le16(blockAlign); le16(BITS_PER_SAMPLE)
        ascii("data"); le32(dataBytes)

        raf.seek(0)
        raf.write(out.toByteArray())
    }
}
