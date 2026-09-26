package com.tyvo.keyboard.polish

import com.tyvo.keyboard.data.PolishProvider
import com.tyvo.keyboard.data.Settings
import com.tyvo.keyboard.net.Http
import com.tyvo.keyboard.net.TyvoException
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
class Polisher(private val settings: Settings) {

    /** Clean-up pass: applies spoken corrections, strips filler, punctuates. */
    suspend fun cleanUp(raw: String): String =
        run(system = Prompts.CLEAN_UP, user = raw)

    /** One-shot rewrite under an arbitrary instruction. */
    suspend fun transform(
        text: String,
        instruction: String,
        provider: PolishProvider? = null,
    ): String = run(Prompts.transform(instruction), text, provider)

    private suspend fun run(
        system: String,
        user: String,
        override: PolishProvider? = null,
    ): String =
        withContext(Dispatchers.IO) {
            if (user.isBlank()) return@withContext user
            when (override ?: settings.polishProvider) {
                PolishProvider.ANTHROPIC -> anthropic(system, user, modelFor(PolishProvider.ANTHROPIC, override))
                PolishProvider.OPENAI -> openAi(system, user, modelFor(PolishProvider.OPENAI, override))
                PolishProvider.MISTRAL -> mistral(system, user, modelFor(PolishProvider.MISTRAL, override))
                PolishProvider.NONE -> user
            }
        }

    /**
     * The model to use. A user's model override applies only to the provider
     * they actually selected -- a probe of a different provider must not
     * inherit it.
     */
    private fun modelFor(p: PolishProvider, override: PolishProvider?): String =
        if (override == null || override == settings.polishProvider) {
            settings.polishModel.ifBlank { defaultModelFor(p) }
        } else {
            defaultModelFor(p)
        }

    /** Output cap scaled to input: rewrites are never much longer than source. */
    private fun maxTokens(user: String): Int =
        (user.length / 2).coerceIn(256, 4096)

    // ---- Anthropic ------------------------------------------------------

    private fun anthropic(system: String, user: String, model: String): String {
        val key = settings.anthropicKey
        if (key.isBlank()) throw TyvoException("Add an Anthropic API key in Tyvo settings.")

        val payload = JSONObject().apply {
            put("model", model)
            put("max_tokens", maxTokens(user))
            put("system", system)
            put("temperature", 0)
            put(
                "messages",
                JSONArray().put(
                    JSONObject().put("role", "user").put("content", user)
                ),
            )
        }

        val req = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .addHeader("x-api-key", key)
            .addHeader("anthropic-version", ANTHROPIC_VERSION)
            .post(payload.toString().toRequestBody(JSON))
            .build()

        val json = Http.json(req, "Polish")
        val blocks = json.optJSONArray("content") ?: return user
        val sb = StringBuilder()
        for (i in 0 until blocks.length()) {
            val b = blocks.optJSONObject(i) ?: continue
            if (b.optString("type") == "text") sb.append(b.optString("text"))
        }
        return sb.toString().trim().ifBlank { user }
    }

    // ---- OpenAI ---------------------------------------------------------

    private fun openAi(system: String, user: String, model: String): String {
        val key = settings.openAiKey
        if (key.isBlank()) throw TyvoException("Add an OpenAI API key in Tyvo settings.")

        val payload = JSONObject().apply {
            put("model", model)
            put("temperature", 0)
            put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", user)),
            )
        }

        val req = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .addHeader("Authorization", "Bearer $key")
            .post(payload.toString().toRequestBody(JSON))
            .build()

        val json = Http.json(req, "Polish")
        val text = json.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content")
            .orEmpty()
        return text.trim().ifBlank { user }
    }

    // ---- Mistral --------------------------------------------------------

    private fun mistral(system: String, user: String, model: String): String {
        val key = settings.mistralKey
        if (key.isBlank()) throw TyvoException("Add a Mistral API key in Tyvo settings.")

        val payload = JSONObject().apply {
            put("model", model)
            put("temperature", 0)
            put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", user)),
            )
        }

        val req = Request.Builder()
            .url("https://api.mistral.ai/v1/chat/completions")
            .addHeader("Authorization", "Bearer $key")
            .post(payload.toString().toRequestBody(JSON))
            .build()

        val json = Http.json(req, "Polish")
        val text = json.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content")
            .orEmpty()
        return text.trim().ifBlank { user }
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private const val ANTHROPIC_VERSION = "2023-06-01"

        const val DEFAULT_ANTHROPIC_MODEL = "claude-haiku-4-5-20251001"
        const val DEFAULT_OPENAI_MODEL = "gpt-4o-mini"
        const val DEFAULT_MISTRAL_MODEL = "mistral-small-latest"

        fun defaultModelFor(p: PolishProvider): String = when (p) {
            PolishProvider.ANTHROPIC -> DEFAULT_ANTHROPIC_MODEL
            PolishProvider.OPENAI -> DEFAULT_OPENAI_MODEL
            PolishProvider.MISTRAL -> DEFAULT_MISTRAL_MODEL
            PolishProvider.NONE -> ""
        }
    }
}
