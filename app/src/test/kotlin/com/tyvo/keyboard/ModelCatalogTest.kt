package com.tyvo.keyboard

import com.tyvo.keyboard.data.ModelCatalog
import com.tyvo.keyboard.data.Provider
import com.tyvo.keyboard.polish.Polisher
import com.tyvo.keyboard.transcribe.Transcriber
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogTest {

    @Test
    fun `every provider default appears in its own dropdown`() {
        // Otherwise the picker opens showing a value it cannot represent.
        Provider.entries.forEach { p ->
            val ids = ModelCatalog.transcribeFor(p).map { it.id }
            assertTrue(
                "default ${Transcriber.defaultModelFor(p)} missing from $p list",
                Transcriber.defaultModelFor(p) in ids,
            )
        }
        Provider.entries.forEach { p ->
            val ids = ModelCatalog.polishFor(p).map { it.id }
            assertTrue(
                "default ${Polisher.defaultModelFor(p)} missing from $p list",
                Polisher.defaultModelFor(p) in ids,
            )
        }
    }

    @Test
    fun `no transcription list offers a model that cannot transcribe`() {
        // voxtral-small is rejected outright by the API as an invalid model.
        ModelCatalog.MISTRAL_TRANSCRIBE.forEach {
            assertFalse("voxtral-small cannot transcribe", it.id.contains("small"))
            assertFalse("tts models cannot transcribe", it.id.contains("tts"))
        }
    }

    @Test
    fun `model ids are unique within each list`() {
        listOf(
            ModelCatalog.OPENAI_TRANSCRIBE,
            ModelCatalog.MISTRAL_TRANSCRIBE,
            ModelCatalog.OPENAI_POLISH,
            ModelCatalog.MISTRAL_POLISH,
        ).forEach { list ->
            val ids = list.map { it.id }
            assertTrue("duplicate model id in $ids", ids.size == ids.toSet().size)
        }
    }

    @Test
    fun `every option carries a label and an explanatory note`() {
        listOf(
            ModelCatalog.OPENAI_TRANSCRIBE,
            ModelCatalog.MISTRAL_TRANSCRIBE,
            ModelCatalog.OPENAI_POLISH,
            ModelCatalog.MISTRAL_POLISH,
        ).flatten().forEach {
            assertTrue("blank label for ${it.id}", it.label.isNotBlank())
            assertTrue("note too thin for ${it.id}", it.note.length > 10)
        }
    }

    @Test
    fun `every provider offers at least one model per stage`() {
        Provider.entries.forEach {
            assertTrue("no transcribe models for $it", ModelCatalog.transcribeFor(it).isNotEmpty())
            assertTrue("no polish models for $it", ModelCatalog.polishFor(it).isNotEmpty())
        }
    }
}
