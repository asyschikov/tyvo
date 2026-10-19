package com.tyvo.keyboard

import com.tyvo.keyboard.data.Provider
import com.tyvo.keyboard.polish.Polisher
import com.tyvo.keyboard.transcribe.Transcriber
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
    fun `every shipped provider has been run against its live API`() {
        // The flag drives a warning in settings, and exists so a provider
        // added from docs alone cannot ship silently. All four have now had
        // both stages exercised against real keys, so all four are true --
        // anything new starts false until someone actually dictates with it.
        Provider.entries.forEach {
            assertTrue("${it.label} has not been run against the live API", it.verified)
        }
    }
}
