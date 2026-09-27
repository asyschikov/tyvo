package com.tyvo.keyboard.transcribe

import com.tyvo.keyboard.data.Settings
import com.tyvo.keyboard.data.Provider
import com.tyvo.keyboard.net.Http
import com.tyvo.keyboard.net.TyvoException
import com.tyvo.keyboard.usage.UsageEvent
import com.tyvo.keyboard.usage.UsageStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import android.util.Base64
import java.io.File

/**
 * Turns a recorded WAV into text.
 */
class Transcriber(
    private val settings: Settings,
    private val usage: UsageStore? = null,
) {

    suspend fun transcribe(audio: File): String = withContext(Dispatchers.IO) {
        when (settings.provider) {
            Provider.OPENAI -> openAi(audio)
            Provider.MISTRAL -> mistral(audio)
            Provider.XAI -> xai(audio)
            Provider.GEMINI -> gemini(audio)
        }
    }

    // ---- OpenAI ---------------------------------------------------------

    private fun openAi(audio: File): String {
        val key = settings.key(Provider.OPENAI)
        if (key.isBlank()) throw TyvoException("Add an OpenAI API key in Tyvo settings.")

        val model = settings.currentTranscribeModel.ifBlank { DEFAULT_OPENAI_MODEL }

        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart(
                "file", audio.name,
                audio.asRequestBody("audio/wav".toMediaType()),
            )
            .addFormDataPart("model", model)
            .addFormDataPart("response_format", "json")
            .apply {
                settings.languageHint.takeIf { it.isNotBlank() }
                    ?.let { addFormDataPart("language", it) }
                // Biasing prompt: nudges spelling of names the model fumbles.
                settings.vocabularyTerms().takeIf { it.isNotEmpty() }?.let {
                    addFormDataPart(
                        "prompt",
                        "Transcribe accurately. Expect these terms: " + it.joinToString(", "),
                    )
                }
            }
            .build()

        val req = Request.Builder()
            .url("https://api.openai.com/v1/audio/transcriptions")
            .addHeader("Authorization", "Bearer $key")
            .post(body)
            .build()

        val json = Http.json(req, "OpenAI transcription")
        usage?.record(
            UsageStore.eventFrom(json, "OpenAI", model, UsageEvent.Kind.TRANSCRIBE)
        )
        return json.optString("text").trim()
    }

    // ---- Mistral --------------------------------------------------------

    private fun mistral(audio: File): String {
        val key = settings.key(Provider.MISTRAL)
        if (key.isBlank()) throw TyvoException("Add a Mistral API key in Tyvo settings.")

        val model = settings.currentTranscribeModel.ifBlank { DEFAULT_MISTRAL_MODEL }

        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart(
                "file", audio.name,
                audio.asRequestBody("audio/wav".toMediaType()),
            )
            .addFormDataPart("model", model)
            .apply {
                // Sent even though Voxtral currently ignores it: measured
                // against the live API, a wrong code changes nothing. It
                // costs one form field, and if Mistral ever honours it the
                // app is already passing it.
                settings.languageHint.takeIf { it.isNotBlank() }
                    ?.let { addFormDataPart("language", it) }
                // Mistral takes bias terms as repeated fields rather than a prompt.
                settings.vocabularyTerms().take(100).forEach {
                    addFormDataPart("context_bias", it)
                }
            }
            .build()

        val req = Request.Builder()
            .url("https://api.mistral.ai/v1/audio/transcriptions")
            .addHeader("Authorization", "Bearer $key")
            .post(body)
            .build()

        val json = Http.json(req, "Mistral transcription")
        usage?.record(
            UsageStore.eventFrom(json, "Mistral", model, UsageEvent.Kind.TRANSCRIBE)
        )
        return json.optString("text").trim()
    }

    // ---- xAI -------------------------------------------------------------

    /**
     * xAI's transcription endpoint.
     *
     * UNVERIFIED against the live API: built from the documented shape, which
     * mirrors OpenAI's multipart form but posts to /v1/stt rather than
     * /v1/audio/transcriptions.
     */
    private fun xai(audio: File): String {
        val key = settings.key(Provider.XAI)
        if (key.isBlank()) throw TyvoException("Add an xAI API key in Tyvo settings.")

        val model = settings.currentTranscribeModel.ifBlank { DEFAULT_XAI_MODEL }

        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", audio.name, audio.asRequestBody("audio/wav".toMediaType()))
            .addFormDataPart("model", model)
            .apply {
                settings.languageHint.takeIf { it.isNotBlank() }
                    ?.let { addFormDataPart("language", it) }
            }
            .build()

        val req = Request.Builder()
            .url("https://api.x.ai/v1/stt")
            .addHeader("Authorization", "Bearer $key")
            .post(body)
            .build()

        val json = Http.json(req, "xAI transcription")
        usage?.record(UsageStore.eventFrom(json, "xAI", model, UsageEvent.Kind.TRANSCRIBE))
        return json.optString("text").trim()
    }

    // ---- Gemini ----------------------------------------------------------

    /**
     * Gemini has no transcription endpoint: audio is multimodal input to the
     * ordinary generateContent call, so the file is base64'd into the JSON
     * body and the model is asked to transcribe it.
     *
     * UNVERIFIED against the live API. The 20MB inline limit is the
     * documented one; longer recordings would need the Files API.
     */
    private fun gemini(audio: File): String {
        val key = settings.key(Provider.GEMINI)
        if (key.isBlank()) throw TyvoException("Add a Gemini API key in Tyvo settings.")

        val bytes = audio.readBytes()
        if (bytes.size > GEMINI_INLINE_LIMIT) {
            throw TyvoException("Recording too long for Gemini (over 20 MB).")
        }
        val model = settings.currentTranscribeModel.ifBlank { DEFAULT_GEMINI_MODEL }

        val languageNote = settings.languageHint.takeIf { it.isNotBlank() }
            ?.let { " The audio is in language code \"$it\"." } ?: ""
        val vocab = settings.vocabularyTerms().takeIf { it.isNotEmpty() }
            ?.let { " Expect these terms: " + it.joinToString(", ") + "." } ?: ""

        val payload = JSONObject().apply {
            put(
                "contents",
                JSONArray().put(
                    JSONObject().put(
                        "parts",
                        JSONArray()
                            .put(
                                JSONObject().put(
                                    "text",
                                    "Transcribe this audio exactly as spoken. Return only " +
                                        "the transcript, with no commentary, labels or " +
                                        "timestamps.$languageNote$vocab",
                                )
                            )
                            .put(
                                JSONObject().put(
                                    "inline_data",
                                    JSONObject()
                                        .put("mime_type", "audio/wav")
                                        .put(
                                            "data",
                                            Base64.encodeToString(bytes, Base64.NO_WRAP),
                                        ),
                                )
                            ),
                    )
                )
            )
            // Deterministic: this is transcription, not composition.
            put("generationConfig", JSONObject().put("temperature", 0))
        }

        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .addHeader("x-goog-api-key", key)
            .post(payload.toString().toRequestBody(JSON))
            .build()

        val json = Http.json(req, "Gemini transcription")
        usage?.record(geminiUsage(json, model, UsageEvent.Kind.TRANSCRIBE))
        return geminiText(json)
    }

    companion object {
        const val DEFAULT_OPENAI_MODEL = "gpt-4o-mini-transcribe"
        const val DEFAULT_MISTRAL_MODEL = "voxtral-mini-latest"

        const val DEFAULT_XAI_MODEL = "grok-voice-transcribe-2.0"
        const val DEFAULT_GEMINI_MODEL = "gemini-2.5-flash-lite"

        /** Gemini's documented cap on inline (base64) request data. */
        const val GEMINI_INLINE_LIMIT = 20 * 1024 * 1024

        private val JSON = "application/json; charset=utf-8".toMediaType()

        fun defaultModelFor(p: Provider): String = when (p) {
            Provider.OPENAI -> DEFAULT_OPENAI_MODEL
            Provider.MISTRAL -> DEFAULT_MISTRAL_MODEL
            Provider.XAI -> DEFAULT_XAI_MODEL
            Provider.GEMINI -> DEFAULT_GEMINI_MODEL
        }

        /** Pulls the text out of a generateContent response. */
        fun geminiText(json: JSONObject): String {
            val parts = json.optJSONArray("candidates")
                ?.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
                ?: return ""
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                sb.append(parts.optJSONObject(i)?.optString("text").orEmpty())
            }
            return sb.toString().trim()
        }

        /** Gemini reports tokens under usageMetadata, not usage. */
        fun geminiUsage(json: JSONObject, model: String, kind: UsageEvent.Kind): UsageEvent {
            val u = json.optJSONObject("usageMetadata")
            return UsageEvent(
                day = UsageStore.dayKey(),
                provider = "Gemini",
                model = model,
                kind = kind,
                promptTokens = u?.optLong("promptTokenCount") ?: 0L,
                completionTokens = u?.optLong("candidatesTokenCount") ?: 0L,
            )
        }
    }
}
