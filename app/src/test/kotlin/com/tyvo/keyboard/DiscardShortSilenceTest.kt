package com.tyvo.keyboard

import com.tyvo.keyboard.ime.Dictation
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscardShortSilenceTest {

    @Test
    fun `a short silent fresh dictation is discarded`() {
        assertTrue(Dictation.shouldDiscard(durationMs = 1_200, enabled = true))
        assertTrue(Dictation.shouldDiscard(durationMs = 2_999, enabled = true))
    }

    @Test
    fun `three seconds or more is kept`() {
        assertFalse(Dictation.shouldDiscard(durationMs = 3_000, enabled = true))
        assertFalse(Dictation.shouldDiscard(durationMs = 45_000, enabled = true))
    }

    @Test
    fun `a retry, with no duration, is never discarded`() {
        // Retrying a saved recording is deliberate; deleting it would lose it.
        assertFalse(Dictation.shouldDiscard(durationMs = null, enabled = true))
    }

    @Test
    fun `the setting turns it off`() {
        assertFalse(Dictation.shouldDiscard(durationMs = 500, enabled = false))
    }
}
