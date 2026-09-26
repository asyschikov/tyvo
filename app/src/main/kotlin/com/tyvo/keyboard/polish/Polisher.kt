package com.tyvo.keyboard.polish

import com.tyvo.keyboard.data.Provider
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
        provider: Provider? = null,
    ): String = run(Prompts.transform(instruction), text, provider)

    private suspend fun run(
        system: String,
        user: String,
        override: Provider? = null,
    ): String =
        withContext(Dispatchers.IO) {
            if (user.isBlank()) return@withContext user
            val p = override ?: settings.provider
            when (p) {
                Provider.OPENAI -> openAi(system, user, modelFor(p, override))
                Provider.MISTRAL -> mistral(system, user, modelFor(p, override))
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

    private fun openAi(system: String, user: String, model: String): String {
        val key = settings.key(Provider.OPENAI)
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
        val key = settings.key(Provider.MISTRAL)
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

        const val DEFAULT_OPENAI_MODEL = "gpt-4o-mini"
        const val DEFAULT_MISTRAL_MODEL = "ministral-3b-latest"

        fun defaultModelFor(p: Provider): String = when (p) {
            Provider.OPENAI -> DEFAULT_OPENAI_MODEL
            Provider.MISTRAL -> DEFAULT_MISTRAL_MODEL
        }
    }
}
