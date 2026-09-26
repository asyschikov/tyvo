package com.tyvo.keyboard.transcribe

import com.tyvo.keyboard.data.Settings
import com.tyvo.keyboard.data.TranscribeProvider
import com.tyvo.keyboard.net.Http
import com.tyvo.keyboard.net.TyvoException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File

/**
 * Turns a recorded WAV into text.
 *
 * Only OpenAI and Mistral are implemented: Anthropic exposes no
 * speech-to-text endpoint and Claude's Messages API accepts no audio
 * content blocks, so Claude cannot serve as a transcription backend.
 */
class Transcriber(private val settings: Settings) {

    suspend fun transcribe(audio: File): String = withContext(Dispatchers.IO) {
        when (settings.transcribeProvider) {
            TranscribeProvider.OPENAI -> openAi(audio)
            TranscribeProvider.MISTRAL -> mistral(audio)
        }
    }

    // ---- OpenAI ---------------------------------------------------------

    private fun openAi(audio: File): String {
        val key = settings.openAiKey
        if (key.isBlank()) throw TyvoException("Add an OpenAI API key in Tyvo settings.")

        val model = settings.transcribeModel.ifBlank { DEFAULT_OPENAI_MODEL }

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
        return json.optString("text").trim()
    }

    // ---- Mistral --------------------------------------------------------

    private fun mistral(audio: File): String {
        val key = settings.mistralKey
        if (key.isBlank()) throw TyvoException("Add a Mistral API key in Tyvo settings.")

        val model = settings.transcribeModel.ifBlank { DEFAULT_MISTRAL_MODEL }

        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart(
                "file", audio.name,
                audio.asRequestBody("audio/wav".toMediaType()),
            )
            .addFormDataPart("model", model)
            .apply {
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
        return json.optString("text").trim()
    }

    companion object {
        const val DEFAULT_OPENAI_MODEL = "gpt-4o-mini-transcribe"
        const val DEFAULT_MISTRAL_MODEL = "voxtral-mini-latest"

        fun defaultModelFor(p: TranscribeProvider): String = when (p) {
            TranscribeProvider.OPENAI -> DEFAULT_OPENAI_MODEL
            TranscribeProvider.MISTRAL -> DEFAULT_MISTRAL_MODEL
        }
    }
}
