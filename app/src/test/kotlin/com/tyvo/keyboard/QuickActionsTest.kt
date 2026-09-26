package com.tyvo.keyboard

import com.tyvo.keyboard.actions.QuickActions
import com.tyvo.keyboard.polish.Prompts
import org.junit.Assert.assertEquals
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
