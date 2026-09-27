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

import com.tyvo.keyboard.polish.Polisher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Em dashes are asked away in the prompt and then removed in code, because a
 * small model reaches for them hard enough that asking is not sufficient --
 * and unlike tone, this is a rule that can be enforced exactly.
 */
class DashStrippingTest {

    private fun clean(input: String) = Polisher.stripDashes(input)

    @Test
    fun `a spaced em dash becomes a comma`() {
        assertEquals(
            "I think the deploy broke, we should roll it back.",
            clean("I think the deploy broke — we should roll it back."),
        )
    }

    @Test
    fun `an unspaced em dash between sentences becomes a full stop`() {
        assertEquals(
            "It broke. We reverted it.",
            clean("It broke—We reverted it."),
        )
    }

    @Test
    fun `en dashes go too`() {
        assertFalse(clean("one – two").contains("–"))
    }

    @Test
    fun `text without dashes is untouched`() {
        val s = "Nothing to do here, it is already fine."
        assertEquals(s, clean(s))
    }

    @Test
    fun `hyphenated words survive`() {
        // A plain hyphen is not a dash and must not be disturbed.
        val s = "state-of-the-art re-entry well-known"
        assertEquals(s, clean(s))
    }

    @Test
    fun `no doubled commas are left behind`() {
        assertFalse(clean("a, — b").contains(", ,"))
        assertFalse(clean("a — , b").contains(",,"))
    }
}
