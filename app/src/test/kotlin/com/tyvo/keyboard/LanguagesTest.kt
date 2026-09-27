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

import com.tyvo.keyboard.data.Languages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguagesTest {

    @Test
    fun `codes are unique and well formed`() {
        val codes = Languages.ALL.map { it.code }
        assertEquals("duplicate language codes", codes.size, codes.toSet().size)
        codes.forEach {
            assertTrue("$it is not a two-letter ISO 639-1 code", it.matches(Regex("[a-z]{2}")))
        }
    }

    @Test
    fun `search matches code, english name and endonym`() {
        // Someone looking for "Русский" and someone looking for "Russian"
        // both need to find it.
        assertTrue(Languages.search("ru").any { it.code == "ru" })
        assertTrue(Languages.search("Russ").any { it.code == "ru" })
        assertTrue(Languages.search("Русск").any { it.code == "ru" })
        assertTrue(Languages.search("deutsch").any { it.code == "de" })
    }

    @Test
    fun `search is case insensitive and ignores surrounding space`() {
        assertTrue(Languages.search("  GERMAN ").any { it.code == "de" })
    }

    @Test
    fun `an empty query returns everything`() {
        assertEquals(Languages.ALL.size, Languages.search("").size)
        assertEquals(Languages.ALL.size, Languages.search("   ").size)
    }

    @Test
    fun `a nonsense query returns nothing`() {
        assertTrue(Languages.search("zzzzq").isEmpty())
    }

    @Test
    fun `byCode is case insensitive and rejects unknown codes`() {
        assertNotNull(Languages.byCode("RU"))
        assertEquals("Russian", Languages.byCode("ru")?.name)
        assertNull(Languages.byCode("xx"))
        assertNull(Languages.byCode(""))
    }

    @Test
    fun `the languages the app was tested against are present`() {
        listOf("en", "ru", "bg", "de").forEach {
            assertNotNull("$it should be offered", Languages.byCode(it))
        }
    }

    @Test
    fun `every entry has a name and an endonym`() {
        Languages.ALL.forEach {
            assertTrue("blank name for ${it.code}", it.name.isNotBlank())
            assertTrue("blank endonym for ${it.code}", it.native.isNotBlank())
        }
    }
}
