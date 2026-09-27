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
 * The Mistral default in particular is a trap: of the voxtral family, only
 * `voxtral-mini-*` transcribes. `voxtral-small-latest` is rejected outright
 * by the API as an invalid transcription model despite the name suggesting a
 * bigger sibling, so an innocent-looking "upgrade" would break dictation.
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
    fun `mistral transcription default is an audio-capable voxtral mini`() {
        val m = Transcriber.DEFAULT_MISTRAL_MODEL
        assertTrue("expected a voxtral-mini model, got $m", m.startsWith("voxtral-mini"))
        assertFalse("voxtral-small does not transcribe", m.contains("small"))
        assertFalse("tts models do not transcribe", m.contains("tts"))
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
