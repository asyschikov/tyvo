package com.tyvo.keyboard

import com.tyvo.keyboard.bubble.Placement
import com.tyvo.keyboard.bubble.Splice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpliceTest {

    @Test
    fun `inserts at the cursor, joining onto a word with a space`() {
        val e = Splice.insert("Hi there", 8, 8, "How are you?")
        assertEquals("Hi there How are you?", e.full)
        assertEquals(Placement(8, " How are you?"), e.placement)
    }

    @Test
    fun `no space after an existing space or at the start`() {
        assertEquals("Hi there", Splice.insert("Hi ", 3, 3, "there").full)
        assertEquals("Hello", Splice.insert("", 0, 0, "Hello").full)
    }

    @Test
    fun `replaces a selection`() {
        val e = Splice.insert("I like cats a lot", 7, 11, "dogs")
        assertEquals("I like dogs a lot", e.full)
        assertEquals(Placement(7, "dogs"), e.placement)
    }

    @Test
    fun `an unreadable or reversed selection still lands sensibly`() {
        assertEquals("Hi there", Splice.insert("Hi", -1, -1, "there").full)
        assertEquals("Hi there", Splice.insert("Hi", 99, 99, "there").full)
        assertEquals("a X d", Splice.insert("a bc d", 4, 2, "X").full)
    }

    @Test
    fun `replace swaps our span and keeps the joining space`() {
        val at = Placement(8, " how are you?")
        val e = Splice.replace("Hi there how are you? Bye", at, "How are you doing?")!!
        assertEquals("Hi there How are you doing? Bye", e.full)
        assertEquals(Placement(8, " How are you doing?"), e.placement)
    }

    @Test
    fun `replace declines once the user has edited our text`() {
        val at = Placement(8, " how are you?")
        assertNull(Splice.replace("Hi there how are YOU?", at, "x"))
        assertNull(Splice.replace("Hi", at, "x"))
    }

    @Test
    fun `remove takes our text out, joining space and all`() {
        val at = Placement(8, " how are you?")
        val e = Splice.remove("Hi there how are you? Bye", at)!!
        assertEquals("Hi there Bye", e.full)
        assertEquals(8, e.placement.end)
    }

    @Test
    fun `remove declines once the user has edited our text`() {
        assertNull(Splice.remove("Hi there how are YOU?", Placement(8, " how are you?")))
    }
}
