package com.tyvo.keyboard

import com.tyvo.keyboard.ime.joinsWithSpace
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpacingTest {

    @Test
    fun `joins onto words and closing marks`() {
        "a9.,!?;:\")".forEach { assertTrue("after '$it'", joinsWithSpace(it)) }
    }

    @Test
    fun `not onto spaces, newlines or opening brackets`() {
        " \n(".forEach { assertFalse("after '$it'", joinsWithSpace(it)) }
    }
}
