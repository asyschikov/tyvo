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

import com.tyvo.keyboard.data.Provider
import com.tyvo.keyboard.polish.Polisher
import com.tyvo.keyboard.transcribe.Transcriber
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the provider matrix.
 *
 * Every provider needs a working default for both stages, and not every
 * model a provider offers can do both jobs -- so the defaults are pinned
 * rather than left to whatever looks newest.
 */
class ProviderDefaultsTest {

    @Test
    fun `every provider has defaults for both stages`() {
        Provider.entries.forEach {
            assertTrue(
                "no default transcription model for $it",
                Transcriber.defaultModelFor(it).isNotBlank(),
            )
            assertTrue(
                "no default polish model for $it",
                Polisher.defaultModelFor(it).isNotBlank(),
            )
        }
    }

    @Test
    fun `mistral transcription default is a voxtral mini`() {
        val m = Transcriber.DEFAULT_MISTRAL_MODEL
        assertTrue("expected a voxtral-mini model, got $m", m.startsWith("voxtral-mini"))
    }

    @Test
    fun `all four providers are offered`() {
        val names = Provider.entries.map { it.name }
        listOf("OPENAI", "MISTRAL", "XAI", "GEMINI").forEach {
            assertTrue("$it missing from providers", it in names)
        }
    }

    @Test
    fun `providers built from docs alone are marked unverified`() {
        // The flag drives a warning in settings. It must stay false until
        // someone has actually dictated through them.
        assertTrue("OpenAI was verified against the live API", Provider.OPENAI.verified)
        assertTrue("Mistral was verified against the live API", Provider.MISTRAL.verified)
        assertFalse("xAI has not been run against the live API", Provider.XAI.verified)
        assertFalse("Gemini has not been run against the live API", Provider.GEMINI.verified)
    }
}
