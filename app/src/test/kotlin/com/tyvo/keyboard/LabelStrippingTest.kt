package com.tyvo.keyboard

import com.tyvo.keyboard.polish.Polisher
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A small model labels its output with a noun the prompt hands it:
 * "message" once, and "note" now that the prompts say "note". A label
 * the speaker did not say must not reach the field -- and a dictation
 * that really starts with the word must keep it.
 */
class LabelStrippingTest {

    private fun clean(output: String, spoken: String) = Polisher.stripLabel(output, spoken)

    @Test
    fun `a leading label with a colon goes`() {
        assertEquals(
            "I love tyvo.",
            clean("Message: I love tyvo.", "I love tyvo"),
        )
    }

    @Test
    fun `a bare leading label goes`() {
        assertEquals(
            "I love tyvo",
            clean("message I love tyvo", "i love tyvo"),
        )
    }

    @Test
    fun `a dashed label goes`() {
        assertEquals(
            "I love tyvo.",
            clean("Message - I love tyvo.", "i love tyvo"),
        )
    }

    @Test
    fun `a label on its own line goes`() {
        assertEquals(
            "I love tyvo.",
            clean("Message:\nI love tyvo.", "i love tyvo"),
        )
    }

    @Test
    fun `a label the speaker actually said stays`() {
        // "message mom that I'm late" is a real dictation: the first word is
        // the speaker's, not a label.
        assertEquals(
            "Message mom I'm late.",
            clean("Message mom I'm late.", "message mom i'm late"),
        )
    }

    @Test
    fun `a label word anywhere in the transcript stays`() {
        // Filler in front: the speaker said "message", so the output's first
        // word is theirs even though the transcript does not start with it.
        assertEquals(
            "Message mom I'm late.",
            clean("Message mom I'm late.", "um message mom i'm late"),
        )
    }

    @Test
    fun `text without a leading label is untouched`() {
        val s = "I love tyvo."
        assertEquals(s, clean(s, "i love tyvo"))
    }

    @Test
    fun `a longer word starting like the label is not a label`() {
        val s = "Messages to mom go in the other chat."
        assertEquals(s, clean(s, "messages to mom go in the other chat"))
    }

    @Test
    fun `a label alone leaves nothing for the caller to fall back on`() {
        assertEquals("", clean("Message:", "i love tyvo"))
    }

    @Test
    fun `a leading note label with a colon goes`() {
        assertEquals(
            "I love tyvo.",
            clean("Note: I love tyvo.", "I love tyvo"),
        )
    }

    @Test
    fun `a bare leading note label goes`() {
        assertEquals(
            "I love tyvo",
            clean("note I love tyvo", "i love tyvo"),
        )
    }

    @Test
    fun `a dashed note label goes`() {
        assertEquals(
            "I love tyvo.",
            clean("Note - I love tyvo.", "i love tyvo"),
        )
    }

    @Test
    fun `a note label on its own line goes`() {
        assertEquals(
            "I love tyvo.",
            clean("Note:\nI love tyvo.", "i love tyvo"),
        )
    }

    @Test
    fun `a note label the speaker actually said stays`() {
        // "note to self, buy milk" is a real dictation: the first word is
        // the speaker's, not a label.
        assertEquals(
            "Note to self, buy milk.",
            clean("Note to self, buy milk.", "note to self, buy milk"),
        )
    }

    @Test
    fun `a note label word anywhere in the transcript stays`() {
        // Filler in front: the speaker said "note", so the output's first
        // word is theirs even though the transcript does not start with it.
        assertEquals(
            "Note to self, buy milk.",
            clean("Note to self, buy milk.", "um note to self, buy milk"),
        )
    }

    @Test
    fun `a longer word starting like the note label is not a label`() {
        val s = "Notes to mom go in the other chat."
        assertEquals(s, clean(s, "notes to mom go in the other chat"))
    }

    @Test
    fun `a note label alone leaves nothing for the caller to fall back on`() {
        assertEquals("", clean("Note:", "i love tyvo"))
    }

    @Test
    fun `the note guard does not keep a message label`() {
        // The guards are per label: the speaker said "note", which must not
        // keep a "message" label the model added.
        assertEquals("Hi.", clean("Message: Hi.", "note to self"))
    }
}
