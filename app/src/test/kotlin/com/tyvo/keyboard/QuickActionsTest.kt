package com.tyvo.keyboard

import com.tyvo.keyboard.actions.QuickActions
import com.tyvo.keyboard.polish.Prompts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickActionsTest {

    @Test
    fun `action ids are unique`() {
        val ids = QuickActions.ALL.map { it.id }
        assertEquals("duplicate action ids", ids.size, ids.toSet().size)
    }

    @Test
    fun `every action is retrievable by id`() {
        QuickActions.ALL.forEach {
            assertEquals(it, QuickActions.byId(it.id))
        }
    }

    @Test
    fun `labels stay short enough for a keyboard chip`() {
        QuickActions.ALL.forEach {
            assertTrue("label too long for a chip: ${it.label}", it.label.length <= 12)
        }
    }

    @Test
    fun `instructions are concrete imperatives`() {
        QuickActions.ALL.forEach {
            assertTrue("instruction too vague: ${it.id}", it.instruction.length > 30)
        }
    }

    @Test
    fun `only translation may change the language`() {
        QuickActions.ALL.filter { it.translating }.let {
            assertEquals("translation should be the sole exception", 1, it.size)
            assertEquals("translate_en", it.first().id)
        }
    }

    @Test
    fun `the language rule never names a specific language`() {
        // Naming one ("if the input is Russian...") made a small model
        // translate English input INTO that language.
        val p = Prompts.transform("Make it formal")
        listOf("Russian", "German", "French", "Spanish").forEach {
            assertFalse(
                "the language rule must stay relative, but mentions $it",
                p.contains("is $it, the output"),
            )
        }
        assertTrue(p.contains("LANGUAGE (absolute)"))
        assertTrue(p.contains("same language"))
    }

    @Test
    fun `translating prompts relax the language lock`() {
        // Compared on collapsed whitespace: the prompt is wrapped, so the
        // prohibition spans a line break in the source.
        fun flat(s: String) = s.replace(Regex("\\s+"), " ")
        val locked = flat(Prompts.transform("Make it formal", translating = false))
        val free = flat(Prompts.transform("Translate into English", translating = true))

        assertTrue(
            "a normal rewrite must be forbidden from translating",
            locked.contains("Never translate or transliterate"),
        )
        assertFalse(
            "a translation must not be told never to translate",
            free.contains("Never translate or transliterate"),
        )
        assertTrue(
            "a translation should be told the language change is expected",
            free.contains("changing language is expected"),
        )
    }

    @Test
    fun `transform prompt embeds the instruction and forbids answering`() {
        val p = Prompts.transform("Make it rhyme")
        assertTrue(p.contains("Make it rhyme"))
        assertTrue(
            "the rewrite prompt must stop the model answering the text",
            p.contains("Never answer"),
        )
    }

    @Test
    fun `cleanup prompt covers spoken correction handling`() {
        val p = Prompts.CLEAN_UP
        listOf("sorry no", "scratch that", "lasagna").forEach {
            assertTrue("clean-up prompt should demonstrate '$it'", p.contains(it))
        }
        assertTrue(p.contains("Never answer"))
    }
}
