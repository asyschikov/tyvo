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

import com.tyvo.keyboard.ime.DictationSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DictationSessionTest {

    private fun dictated(raw: String, polished: String) = DictationSession().apply {
        begin(raw)
        setPolished(polished)
    }

    @Test
    fun `actions transform the base, never each other`() {
        // The whole point of the base: tapping Formal then Shorter must give
        // a short version of the polished text, not a short version of the
        // formal rewrite.
        val s = dictated("raw text", "Polished text.")
        s.setVariant("FORMAL VERSION", "formal")
        assertEquals("Polished text.", s.base)
        assertEquals("FORMAL VERSION", s.current)

        s.setVariant("Short.", "shorter")
        assertEquals("the base must survive a second action", "Polished text.", s.base)
        assertEquals("Short.", s.current)
        assertEquals("shorter", s.activeAction)
    }

    @Test
    fun `undo returns to the base and clears the active action`() {
        val s = dictated("raw text", "Polished text.")
        s.setVariant("FORMAL", "formal")
        assertTrue(s.canUndo)

        assertEquals("Polished text.", s.undo())
        assertEquals("Polished text.", s.current)
        assertNull(s.activeAction)
        assertFalse(s.canUndo)
        assertNull("nothing left to undo", s.undo())
    }

    @Test
    fun `a variant equal to the base is not a variant`() {
        val s = dictated("raw text", "Polished text.")
        s.setVariant("Polished text.", "formal")
        assertFalse("an unchanged rewrite should not be undoable", s.canUndo)
        assertNull(s.activeAction)
    }

    @Test
    fun `unpolish swaps the base for the raw transcript`() {
        val s = dictated("um raw text", "Polished text.")
        assertTrue(s.canUnpolish)
        assertTrue(s.isPolished)

        assertEquals("um raw text", s.unpolish())
        assertEquals("um raw text", s.base)
        assertEquals("um raw text", s.current)
        assertFalse(s.isPolished)
        assertFalse("nothing left to unpolish", s.canUnpolish)
    }

    @Test
    fun `unpolish is lossy - the polished version is gone`() {
        val s = dictated("um raw text", "Polished text.")
        s.unpolish()
        // Only a fresh polish brings a polished version back, and it becomes
        // the new base rather than restoring the discarded one.
        s.setPolished("Newly polished.")
        assertEquals("Newly polished.", s.base)
        assertTrue(s.isPolished)
        assertEquals("um raw text", s.rawTranscript)
    }

    @Test
    fun `unpolish discards an applied action`() {
        val s = dictated("um raw text", "Polished text.")
        s.setVariant("FORMAL", "formal")
        s.unpolish()
        assertEquals("um raw text", s.current)
        assertNull(s.activeAction)
        assertFalse(s.canUndo)
    }

    @Test
    fun `polishing does not change the raw transcript`() {
        val s = dictated("um raw text", "First polish.")
        s.setPolished("Second polish.")
        assertEquals("um raw text", s.rawTranscript)
        assertEquals("Second polish.", s.base)
    }

    @Test
    fun `an unpolished session cannot be unpolished again`() {
        val s = DictationSession()
        s.begin("just raw")
        assertFalse("never polished, so nothing to revert", s.canUnpolish)
        assertNull(s.unpolish())
    }

    @Test
    fun `a no-op polish leaves nothing to unpolish`() {
        val s = DictationSession()
        s.begin("Already clean.")
        s.setPolished("Already clean.")
        assertFalse(s.canUnpolish)
    }

    @Test
    fun `begin resets everything`() {
        val s = dictated("old raw", "Old polished.")
        s.setVariant("OLD FORMAL", "formal")
        s.begin("new raw")
        assertEquals("new raw", s.current)
        assertEquals("new raw", s.base)
        assertFalse(s.canUndo)
        assertFalse(s.isPolished)
        assertNull(s.activeAction)
    }

    @Test
    fun `a session seeded with polished text can still be unpolished`() {
        // Only the polished text is inserted now, so the session is seeded
        // with the transcript and then promoted. If that seeding were skipped
        // the raw text would be lost and "Undo clean-up" would do nothing.
        val s = DictationSession()
        s.begin("um raw text")
        s.setPolished("Raw text.")

        assertTrue("there must be something to revert to", s.canUnpolish)
        assertEquals("Raw text.", s.current)
        assertEquals("um raw text", s.unpolish())
    }

    @Test
    fun `clear ends the session`() {
        val s = dictated("raw", "Polished.")
        s.setVariant("VARIANT", "formal")
        s.clear()
        assertFalse(s.isActive)
        assertFalse(s.canUndo)
        assertFalse(s.canUnpolish)
        assertEquals("", s.current)
    }
}
