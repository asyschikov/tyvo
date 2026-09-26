package com.tyvo.keyboard

import com.tyvo.keyboard.data.PolishProvider
import com.tyvo.keyboard.data.TranscribeProvider
import com.tyvo.keyboard.polish.Polisher
import com.tyvo.keyboard.transcribe.Transcriber
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the provider matrix.
 *
 * The Mistral default in particular is a trap: of the voxtral family, only
 * `voxtral-mini-*` reports audio_transcription capability. `voxtral-small`
 * does not transcribe despite the name suggesting a bigger sibling, so an
 * innocent-looking "upgrade" would break dictation.
 */
class ProviderDefaultsTest {

    @Test
    fun `every transcribe provider has a non-blank default model`() {
        TranscribeProvider.entries.forEach {
            assertTrue(
                "no default transcription model for $it",
                Transcriber.defaultModelFor(it).isNotBlank(),
            )
        }
    }

    @Test
    fun `every polish provider except NONE has a default model`() {
        PolishProvider.entries.filter { it != PolishProvider.NONE }.forEach {
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
        assertFalse(
            "voxtral-small does not support audio transcription",
            m.contains("small"),
        )
        assertFalse("tts models do not transcribe", m.contains("tts"))
    }

    @Test
    fun `anthropic is not offered as a transcription provider`() {
        assertTrue(
            "Anthropic must not appear as a transcription option",
            TranscribeProvider.entries.none { it.name.contains("ANTHROPIC") },
        )
    }

    @Test
    fun `both providers are available for polish`() {
        val names = PolishProvider.entries.map { it.name }
        listOf("OPENAI", "MISTRAL").forEach {
            assertTrue("$it missing from polish providers", it in names)
        }
    }

    @Test
    fun `anthropic is absent everywhere`() {
        assertTrue(
            "Anthropic should no longer appear as a polish provider",
            PolishProvider.entries.none { it.name.contains("ANTHROPIC") },
        )
    }
}
