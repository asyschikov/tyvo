package com.tyvo.keyboard

import com.tyvo.keyboard.ime.DictationSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DictationSessionTest {

    @Test
    fun `begin sets text and clears history`() {
        val s = DictationSession()
        s.begin("first")
        s.advance("second")
        s.begin("fresh")
        assertEquals("fresh", s.current)
        assertFalse("a new dictation must not undo into the previous one", s.canUndo)
    }

    @Test
    fun `advance records history and undo walks back one step`() {
        val s = DictationSession()
        s.begin("raw")
        s.advance("polished")
        s.advance("shortened")
        assertEquals("shortened", s.current)

        assertEquals("polished", s.undo())
        assertEquals("polished", s.current)
        assertEquals("raw", s.undo())
        assertEquals("raw", s.current)
        assertFalse(s.canUndo)
        assertNull(s.undo())
    }

    @Test
    fun `advance ignores a no-op transformation`() {
        val s = DictationSession()
        s.begin("same")
        s.advance("same")
        assertFalse("an unchanged rewrite should not create an undo step", s.canUndo)
    }

    @Test
    fun `history is bounded`() {
        val s = DictationSession()
        s.begin("0")
        repeat(40) { s.advance("step$it") }
        var steps = 0
        while (s.undo() != null) steps++
        assertTrue("history must stay bounded, was $steps", steps <= 20)
    }

    @Test
    fun `clear ends the session`() {
        val s = DictationSession()
        s.begin("text")
        s.advance("more")
        s.clear()
        assertFalse(s.isActive)
        assertFalse(s.canUndo)
        assertEquals("", s.current)
    }
}
