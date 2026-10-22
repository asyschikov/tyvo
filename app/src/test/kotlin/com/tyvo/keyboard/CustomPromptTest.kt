package com.tyvo.keyboard

import com.tyvo.keyboard.actions.QuickAction
import com.tyvo.keyboard.actions.QuickActions
import com.tyvo.keyboard.polish.Correction
import com.tyvo.keyboard.polish.Prompts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomPromptTest {

    @Test
    fun `no rules leaves the built prompt exactly as it was`() {
        assertEquals(
            Prompts.cleanUp(Correction.defaults),
            Prompts.cleanUp(Correction.defaults, ownRules = "  \n "),
        )
    }

    @Test
    fun `the speaker's rules are the last numbered step`() {
        val p = Prompts.cleanUp(Correction.defaults, "Never use exclamation marks\nWrite Tyvo with a capital T")
        val n = Correction.defaults.size + 1
        assertTrue(p.contains("$n. THE SPEAKER'S OWN RULES"))
        // Continuation lines are indented under the step like every other.
        assertTrue(p.contains("\n   Never use exclamation marks\n   Write Tyvo with a capital T"))
        assertTrue(p.indexOf("OWN RULES") < p.indexOf("Hard rules:"))
    }

    @Test
    fun `rules still apply with every correction off`() {
        val p = Prompts.cleanUp(emptySet(), "Keep my swearing")
        assertTrue(p.contains("1. THE SPEAKER'S OWN RULES"))
        assertFalse(p.contains("Return the transcript unchanged"))
    }

    @Test
    fun `an edited action keeps its place, label and language lock`() {
        val merged = QuickActions.merged(mapOf("formal" to "Make it stiff."), emptyList())
        assertEquals(QuickActions.ALL.map { it.id }, merged.map { it.id })
        val formal = merged.single { it.id == "formal" }
        assertEquals("Make it stiff.", formal.instruction)
        assertEquals(QuickActions.FORMAL.label, formal.label)
        assertEquals(QuickActions.TRANSLATE_EN, merged.single { it.id == "translate_en" })
    }

    @Test
    fun `a blank edit falls back to the built-in instruction`() {
        val merged = QuickActions.merged(mapOf("formal" to "  "), emptyList())
        assertEquals(QuickActions.FORMAL, merged.single { it.id == "formal" })
    }

    @Test
    fun `custom actions follow the built-ins and never translate`() {
        val mine = QuickActions.custom("custom_1", " Pirate ", " Make it sound like a pirate. ")
        val merged = QuickActions.merged(emptyMap(), listOf(mine))
        assertEquals(mine, merged.last())
        assertEquals("Pirate", mine.label)
        assertEquals(QuickAction.Group.CUSTOM, mine.group)
        assertFalse(mine.translating)
    }

    @Test
    fun `half-made custom actions stay off the bar`() {
        val merged = QuickActions.merged(emptyMap(), listOf(QuickActions.custom("custom_2", "Empty", "")))
        assertEquals(QuickActions.ALL.size, merged.size)
    }
}
