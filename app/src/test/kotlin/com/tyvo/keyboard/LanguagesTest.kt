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
