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
    suspend fun cleanUp(raw: String, system: String = settings.cleanupSystemPrompt()): String =
        run(system = system, user = raw)

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
            key = settings.key(Provider.MISTRAL), label = "Mistral",
            noReasoning = reasonsByDefault(model))

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
        noReasoning: Boolean = false,
    ): String {
        if (key.isBlank()) throw TyvoException("Add a $label API key in Tyvo settings.")

        val payload = JSONObject().apply {
            put("model", model)
            put("temperature", 0)
            // Already every provider's default, but Mistral Large 4 rejects
            // temperature 0 with 400 unless top_p is stated as 1.
            put("top_p", 1)
            if (noReasoning) put("reasoning_effort", "none")
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
        return stripLabel(stripDashes(text.trim()), original).ifBlank { original }
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
        return stripLabel(stripDashes(Transcriber.geminiText(json)), original)
            .ifBlank { original }
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

        /**
         * Removes a leading label the model put on its output: "message",
         * and "note", which the prompts say where they once said "message".
         *
         * The prompts no longer offer "message", but a small chat model
         * labels its output with a noun anyway, and those were the two it
         * reached for: every dictation came back as "message I love tyvo",
         * a word the speaker never said. Only a leading label is removed,
         * and only when the transcript does not contain the word -- clean-up
         * never adds words, so a label the speaker did not say was put there
         * by the model. A dictation that really starts "note to self..."
         * keeps its first word, because a keyboard that quietly rewrites
         * your voice is worse than one that leaves a stray label in.
         */
        fun stripLabel(text: String, spoken: String): String {
            var out = text
            LABELS.forEach { (leading, spokenWord) ->
                if (leading.containsMatchIn(out) && !spokenWord.containsMatchIn(spoken)) {
                    out = leading.replaceFirst(out, "")
                }
            }
            return out
        }

        /**
         * The labels a small model puts on its output, each with the guard
         * that keeps the speaker's own word: a leading "message" or "note"
         * wearing the punctuation a label carries, and the same word
         * anywhere in what the speaker said.
         */
        private val LABELS = listOf(
            Regex("(?i)^message\\b[\\s:,.!?;–—-]*") to Regex("(?i)\\bmessage\\b"),
            Regex("(?i)^note\\b[\\s:,.!?;–—-]*") to Regex("(?i)\\bnote\\b"),
        )

        private val JSON = "application/json; charset=utf-8".toMediaType()

        const val DEFAULT_OPENAI_MODEL = "gpt-4o-mini"
        // Small 4, pinned rather than mistral-small-latest, which can move
        // under us. On the clean-up eval it ties Ministral 8B in English
        // (60/68) with fewer swapped words, and leads in German, Dutch and
        // Russian (49/66 against 44/66).
        const val DEFAULT_MISTRAL_MODEL = "mistral-small-2603"

        const val DEFAULT_XAI_MODEL = "grok-4.20-0309-non-reasoning"
        const val DEFAULT_GEMINI_MODEL = "gemini-3.5-flash-lite"

        /**
         * Mistral models that take reasoning_effort. Large 4 reasons unless
         * told not to: about 1,350 hidden tokens and 16 s for one sentence,
         * returned as thinking blocks instead of text. The Ministrals and
         * Nemo reject the field with 400, so it goes only to these.
         */
        fun reasonsByDefault(model: String): Boolean =
            listOf("mistral-small", "mistral-medium", "mistral-large", "magistral")
                .any { model.startsWith(it) }

        fun defaultModelFor(p: Provider): String = when (p) {
            Provider.OPENAI -> DEFAULT_OPENAI_MODEL
            Provider.MISTRAL -> DEFAULT_MISTRAL_MODEL
            Provider.XAI -> DEFAULT_XAI_MODEL
            Provider.GEMINI -> DEFAULT_GEMINI_MODEL
        }
    }
}
