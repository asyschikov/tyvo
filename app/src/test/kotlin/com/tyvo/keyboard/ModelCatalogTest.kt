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
    fun `mistral transcription only offers voxtral mini models`() {
        ModelCatalog.MISTRAL_TRANSCRIBE.forEach {
            assertTrue(
                "${it.id} is not a voxtral-mini model",
                it.id.startsWith("voxtral-mini"),
            )
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
