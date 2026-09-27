/*
 * Tyvo -- a voice keyboard that writes what you meant.
 * Copyright (C) 2026 Andrey Syschikov
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.tyvo.keyboard

import com.tyvo.keyboard.history.Recording
import com.tyvo.keyboard.history.RecordingStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The store's job is to make sure nothing the user dictated is ever lost, so
 * most of these tests are about audio surviving the ways transcription fails.
 */
class RecordingStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var store: RecordingStore

    @Before
    fun setUp() {
        store = RecordingStore(tmp.newFolder())
    }

    private fun wav(name: String = "take.wav", bytes: Int = 128): File =
        File(tmp.newFolder(), name).apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(bytes) { 7 })
        }

    @Test
    fun `a captured recording is stored before transcription is attempted`() {
        val rec = store.addPending(wav(), durationMs = 4200)
        assertEquals(Recording.Status.PENDING, rec.status)
        assertEquals(4200, rec.durationMs)
        assertNotNull("audio must be retained for a pending recording", store.audioFor(rec))
        assertEquals(1, store.all().size)
    }

    @Test
    fun `the source file is moved into the store, not merely referenced`() {
        val source = wav()
        val rec = store.addPending(source, 1000)
        assertFalse("the original should not be left behind", source.exists())
        assertTrue(store.audioFor(rec)!!.exists())
    }

    @Test
    fun `a failed transcription keeps its audio so it can be retried`() {
        val rec = store.addPending(wav(), 1000)
        store.markFailed(rec.id, "No connection.")

        val reloaded = store.get(rec.id)!!
        assertEquals(Recording.Status.FAILED, reloaded.status)
        assertEquals("No connection.", reloaded.error)
        assertNotNull("audio must survive a failure", store.audioFor(reloaded))
        assertTrue(reloaded.hasAudio)
    }

    @Test
    fun `repeated failures accumulate an attempt count`() {
        val rec = store.addPending(wav(), 1000)
        store.markFailed(rec.id, "No connection.")
        store.markFailed(rec.id, "Timed out.")
        val reloaded = store.get(rec.id)!!
        assertEquals(2, reloaded.attempts)
        assertEquals("the latest error should win", "Timed out.", reloaded.error)
        assertNotNull(store.audioFor(reloaded))
    }

    @Test
    fun `audio is only discarded once text exists to replace it`() {
        val rec = store.addPending(wav(), 1000)
        val audio = store.audioFor(rec)!!
        store.markDone(rec.id, "Hello there.")

        val reloaded = store.get(rec.id)!!
        assertEquals(Recording.Status.DONE, reloaded.status)
        assertEquals("Hello there.", reloaded.text)
        assertFalse("audio should be freed once transcribed", audio.exists())
        assertNull(reloaded.audioFile)
    }

    @Test
    fun `a retry that succeeds clears the failure`() {
        val rec = store.addPending(wav(), 1000)
        store.markFailed(rec.id, "No connection.")
        store.markDone(rec.id, "Recovered text.")

        val reloaded = store.get(rec.id)!!
        assertEquals(Recording.Status.DONE, reloaded.status)
        assertNull("a successful retry must clear the error", reloaded.error)
        assertFalse(reloaded.needsAttention)
    }

    @Test
    fun `unfinished recordings are pinned above transcribed ones`() {
        val done = store.addPending(wav(), 1000)
        store.markDone(done.id, "Older but finished.")
        Thread.sleep(5)
        val failed = store.addPending(wav(), 1000)
        store.markFailed(failed.id, "No connection.")

        val all = store.all()
        assertEquals("the failure must come first", failed.id, all.first().id)
        assertEquals(1, store.needingAttention().size)
    }

    @Test
    fun `the index survives a reopen`() {
        val dir = tmp.newFolder()
        val first = RecordingStore(dir)
        val rec = first.addPending(wav(), 2500)
        first.markFailed(rec.id, "No connection.")

        val second = RecordingStore(dir)
        val reloaded = second.get(rec.id)
        assertNotNull("recordings must outlive the process", reloaded)
        assertEquals(Recording.Status.FAILED, reloaded!!.status)
        assertEquals(2500, reloaded.durationMs)
        assertNotNull(second.audioFor(reloaded))
    }

    @Test
    fun `a corrupt index does not take the audio with it`() {
        val dir = tmp.newFolder()
        val first = RecordingStore(dir)
        first.addPending(wav(), 1000)

        File(dir, "recordings/index.json").writeText("{ this is not json")

        val second = RecordingStore(dir)
        assertTrue("a damaged index should read as empty, not crash", second.all().isEmpty())
        // The audio itself is still on disk and recoverable by hand.
        assertTrue(second.audioBytes() > 0)
    }

    @Test
    fun `deleting a recording removes its audio`() {
        val rec = store.addPending(wav(), 1000)
        val audio = store.audioFor(rec)!!
        store.delete(rec.id)
        assertTrue(store.all().isEmpty())
        assertFalse(audio.exists())
    }

    // ---- recovery --------------------------------------------------------

    @Test
    fun `audio orphaned by a lost index is recovered`() {
        val dir = tmp.newFolder()
        val first = RecordingStore(dir)
        first.addPending(wav(), 1000)

        // Simulate the index being corrupted or wiped while the audio remains.
        File(dir, "recordings/index.json").delete()

        val second = RecordingStore(dir)
        assertTrue("index is gone", second.all().isEmpty())
        assertEquals(1, second.recoverOrphanedAudio())

        val recovered = second.all().single()
        assertEquals(Recording.Status.PENDING, recovered.status)
        assertNotNull("the recording must be retryable again", second.audioFor(recovered))
    }

    @Test
    fun `recovery derives a duration from the wav header`() {
        val dir = tmp.newFolder()
        val store = RecordingStore(dir)
        // 44-byte header + one second of 16 kHz mono 16-bit audio.
        store.addPending(wav(bytes = 44 + 16_000 * 2), 0)
        File(dir, "recordings/index.json").delete()

        val reopened = RecordingStore(dir)
        reopened.recoverOrphanedAudio()
        assertEquals(1000, reopened.all().single().durationMs)
    }

    @Test
    fun `recovery leaves known recordings alone`() {
        val rec = store.addPending(wav(), 1000)
        assertEquals("nothing is orphaned", 0, store.recoverOrphanedAudio())
        assertEquals(1, store.all().size)
        assertEquals(rec.id, store.all().single().id)
    }

    @Test
    fun `recovery does not resurrect audio that was correctly deleted`() {
        val rec = store.addPending(wav(), 1000)
        store.markDone(rec.id, "transcribed")
        assertEquals(0, store.recoverOrphanedAudio())
        assertEquals(1, store.all().size)
    }

    // ---- expiry ----------------------------------------------------------

    private val DAY = 24 * 60 * 60 * 1000L

    @Test
    fun `audio older than a day is expired but its text is kept`() {
        val rec = store.addPending(wav(), 1000)
        store.markDone(rec.id, "spoken words")
        // markDone already frees the audio, so re-add one to age out.
        val stale = store.addPending(wav(), 1000)
        store.markFailed(stale.id, "No connection.")

        val freed = store.expireAudio(now = System.currentTimeMillis() + DAY + 1000)
        assertEquals(1, freed)

        val reloaded = store.get(stale.id)!!
        assertNull("audio should be gone", reloaded.audioFile)
        assertEquals("the row must survive", Recording.Status.FAILED, reloaded.status)
        assertEquals("transcripts are never expired", "spoken words", store.get(rec.id)!!.text)
    }

    @Test
    fun `expiry explains why a recording can no longer be retried`() {
        val rec = store.addPending(wav(), 1000)
        store.markFailed(rec.id, "No connection.")
        store.expireAudio(now = System.currentTimeMillis() + DAY + 1000)
        assertTrue(store.get(rec.id)!!.error!!.contains("Audio expired"))
    }

    @Test
    fun `recent audio is left alone`() {
        val rec = store.addPending(wav(), 1000)
        store.markFailed(rec.id, "No connection.")
        assertEquals(0, store.expireAudio())
        assertNotNull(store.audioFor(store.get(rec.id)!!))
    }

    @Test
    fun `keepFailed exempts failures but not successes`() {
        val failed = store.addPending(wav(), 1000)
        store.markFailed(failed.id, "No connection.")
        val pendingRec = store.addPending(wav(), 1000)

        val later = System.currentTimeMillis() + DAY + 1000
        assertEquals("neither should be touched", 0, store.expireAudio(keepFailed = true, now = later))
        assertNotNull(store.audioFor(store.get(failed.id)!!))
        assertNotNull(store.audioFor(store.get(pendingRec.id)!!))

        // Without the exemption they expire on schedule.
        assertEquals(2, store.expireAudio(keepFailed = false, now = later))
    }

    @Test
    fun `expiry is idempotent`() {
        val rec = store.addPending(wav(), 1000)
        store.markFailed(rec.id, "No connection.")
        val later = System.currentTimeMillis() + DAY + 1000
        assertEquals(1, store.expireAudio(now = later))
        assertEquals("nothing left to free", 0, store.expireAudio(now = later))
    }

    @Test
    fun `updateText revises a transcript without resurrecting audio`() {
        val rec = store.addPending(wav(), 1000)
        store.markDone(rec.id, "raw text")
        store.updateText(rec.id, "polished text")
        val reloaded = store.get(rec.id)!!
        assertEquals("polished text", reloaded.text)
        assertNull(reloaded.audioFile)
    }
}
