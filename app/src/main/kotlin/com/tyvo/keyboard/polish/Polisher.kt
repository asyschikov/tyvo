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
package com.tyvo.keyboard.polish

import com.tyvo.keyboard.data.Provider
import com.tyvo.keyboard.data.Settings
import com.tyvo.keyboard.net.Http
import com.tyvo.keyboard.net.TyvoException
import com.tyvo.keyboard.transcribe.Transcriber
import com.tyvo.keyboard.usage.UsageEvent
import com.tyvo.keyboard.usage.UsageStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Runs the LLM text passes: the automatic clean-up after dictation, and the
 * on-demand quick-action rewrites.
 *
 * All three providers are usable here, unlike transcription -- this stage is
 * pure text in, text out.
 */
class Polisher(
    private val settings: Settings,
    private val usage: UsageStore? = null,
) {

    /** Clean-up pass: applies spoken corrections, strips filler, punctuates. */
    suspend fun cleanUp(raw: String): String =
        run(system = Prompts.cleanUp(settings.corrections()), user = raw)

    /** One-shot rewrite under an arbitrary instruction. */
    suspend fun transform(
        text: String,
        instruction: String,
        provider: Provider? = null,
        translating: Boolean = false,
    ): String = run(Prompts.transform(instruction, translating), text, provider)

    private suspend fun run(
        system: String,
        user: String,
        override: Provider? = null,
    ): String =
        withContext(Dispatchers.IO) {
            if (user.isBlank()) return@withContext user
            val wrapped = Prompts.wrapInput(user)
            val p = override ?: settings.provider
            when (p) {
                Provider.OPENAI -> openAi(system, wrapped, user, modelFor(p, override))
                Provider.MISTRAL -> mistral(system, wrapped, user, modelFor(p, override))
                // xAI's chat API is OpenAI-shaped, so it differs only in host.
                Provider.XAI -> chat(
                    system, wrapped, user, modelFor(p, override),
                    url = "https://api.x.ai/v1/chat/completions",
                    key = settings.key(Provider.XAI),
                    label = "xAI",
                )
                Provider.GEMINI -> gemini(system, wrapped, user, modelFor(p, override))
            }
        }


    /**
     * The model to use. A user's model override applies only to the provider
     * they actually selected -- a probe of a different provider must not
     * inherit it.
     */
    private fun modelFor(p: Provider, override: Provider?): String =
        if (override == null || override == settings.provider) {
            settings.currentPolishModel.ifBlank { defaultModelFor(p) }
        } else {
            settings.polishModel(p).ifBlank { defaultModelFor(p) }
        }

    /** Output cap scaled to input: rewrites are never much longer than source. */
    private fun maxTokens(user: String): Int =
        (user.length / 2).coerceIn(256, 4096)

    // ---- OpenAI ---------------------------------------------------------

    private fun openAi(system: String, sent: String, original: String, model: String): String =
        chat(system, sent, original, model,
            url = "https://api.openai.com/v1/chat/completions",
            key = settings.key(Provider.OPENAI), label = "OpenAI")

    private fun mistral(system: String, sent: String, original: String, model: String): String =
        chat(system, sent, original, model,
            url = "https://api.mistral.ai/v1/chat/completions",
            key = settings.key(Provider.MISTRAL), label = "Mistral")

    /**
     * One OpenAI-shaped chat call. OpenAI, Mistral and xAI differ only in
     * host and key, so they share this.
     */
    private fun chat(
        system: String,
        sent: String,
        original: String,
        model: String,
        url: String,
        key: String,
        label: String,
    ): String {
        if (key.isBlank()) throw TyvoException("Add a $label API key in Tyvo settings.")

        val payload = JSONObject().apply {
            put("model", model)
            put("temperature", 0)
            put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", sent)),
            )
        }

        val req = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $key")
            .post(payload.toString().toRequestBody(JSON))
            .build()

        val json = Http.json(req, "Polish")
        usage?.record(UsageStore.eventFrom(json, label, model, UsageEvent.Kind.POLISH))
        val text = json.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content")
            .orEmpty()
        return stripDashes(text.trim()).ifBlank { original }
    }

    /**
     * Gemini's generateContent, which takes the system prompt as a separate
     * systemInstruction rather than a message with a role.
     *
     * UNVERIFIED against the live API.
     */
    private fun gemini(system: String, sent: String, original: String, model: String): String {
        val key = settings.key(Provider.GEMINI)
        if (key.isBlank()) throw TyvoException("Add a Gemini API key in Tyvo settings.")

        val payload = JSONObject().apply {
            put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))),
            )
            put(
                "contents",
                JSONArray().put(
                    JSONObject().put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", sent)),
                    )
                )
            )
            put(
                "generationConfig",
                JSONObject()
                    .put("temperature", 0)
                    .put("maxOutputTokens", maxTokens(original)),
            )
        }

        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .addHeader("x-goog-api-key", key)
            .post(payload.toString().toRequestBody(JSON))
            .build()

        val json = Http.json(req, "Polish")
        usage?.record(Transcriber.geminiUsage(json, model, UsageEvent.Kind.POLISH))
        return stripDashes(Transcriber.geminiText(json)).ifBlank { original }
    }

    companion object {

    /**
         * Replaces em and en dashes with punctuation someone would actually type.
         *
         * The prompts ask for this too, but a small model reaches for an em dash
         * hard enough that asking is not sufficient -- and this is a rule that can
         * be enforced exactly, so it should be. A spaced dash becomes a comma,
         * which is what the dash was standing in for; an unspaced one becomes a
         * full stop only when what follows looks like a new sentence, since
         * "state-of-the-art" style compounds must survive.
         */
            fun stripDashes(text: String): String = text
            .replace(Regex("\\s+[—–]\\s+"), ", ")
            .replace(Regex("(?<=[a-z,;:])[—–](?=[A-Z])"), ". ")
            .replace("—", ", ")
            .replace("–", ", ")
            .replace(Regex(",\\s*,"), ",")
            .replace(Regex("\\s+,"), ",")

        private val JSON = "application/json; charset=utf-8".toMediaType()

        const val DEFAULT_OPENAI_MODEL = "gpt-4o-mini"
        const val DEFAULT_MISTRAL_MODEL = "ministral-3b-latest"

        const val DEFAULT_XAI_MODEL = "grok-build-0.1"
        const val DEFAULT_GEMINI_MODEL = "gemini-2.5-flash-lite"

        fun defaultModelFor(p: Provider): String = when (p) {
            Provider.OPENAI -> DEFAULT_OPENAI_MODEL
            Provider.MISTRAL -> DEFAULT_MISTRAL_MODEL
            Provider.XAI -> DEFAULT_XAI_MODEL
            Provider.GEMINI -> DEFAULT_GEMINI_MODEL
        }
    }
}
