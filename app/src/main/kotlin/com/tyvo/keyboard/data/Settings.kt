package com.tyvo.keyboard.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Provider used to turn recorded audio into text.
 * Anthropic is deliberately absent: Claude has no audio input and no
 * transcription endpoint, so it can only ever be a polish backend.
 */
enum class TranscribeProvider(val label: String) {
    OPENAI("OpenAI"),
    MISTRAL("Mistral");
}

/** Provider used for the LLM text-polish / correction pass. */
enum class PolishProvider(val label: String) {
    ANTHROPIC("Anthropic"),
    OPENAI("OpenAI"),
    MISTRAL("Mistral"),
    NONE("Off (raw transcript)");
}

/**
 * Persisted config. API keys live in EncryptedSharedPreferences; everything
 * else is plain prefs. Reads are cheap and happen on the IME hot path, so
 * values are read directly rather than through a flow.
 */
class Settings(context: Context) {

    private val appContext = context.applicationContext

    private val secure: SharedPreferences by lazy {
        val key = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            "tyvo_secure",
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private val plain: SharedPreferences =
        appContext.getSharedPreferences("tyvo_prefs", Context.MODE_PRIVATE)

    // ---- API keys -------------------------------------------------------

    var anthropicKey: String
        get() = secure.getString(KEY_ANTHROPIC, "").orEmpty()
        set(v) = secure.edit().putString(KEY_ANTHROPIC, v.trim()).apply()

    var openAiKey: String
        get() = secure.getString(KEY_OPENAI, "").orEmpty()
        set(v) = secure.edit().putString(KEY_OPENAI, v.trim()).apply()

    var mistralKey: String
        get() = secure.getString(KEY_MISTRAL, "").orEmpty()
        set(v) = secure.edit().putString(KEY_MISTRAL, v.trim()).apply()

    fun keyFor(p: TranscribeProvider): String = when (p) {
        TranscribeProvider.OPENAI -> openAiKey
        TranscribeProvider.MISTRAL -> mistralKey
    }

    fun keyFor(p: PolishProvider): String = when (p) {
        PolishProvider.ANTHROPIC -> anthropicKey
        PolishProvider.OPENAI -> openAiKey
        PolishProvider.MISTRAL -> mistralKey
        PolishProvider.NONE -> ""
    }

    // ---- Provider selection ---------------------------------------------

    var transcribeProvider: TranscribeProvider
        get() = runCatching {
            TranscribeProvider.valueOf(
                plain.getString(KEY_TP, null) ?: TranscribeProvider.OPENAI.name
            )
        }.getOrDefault(TranscribeProvider.OPENAI)
        set(v) = plain.edit().putString(KEY_TP, v.name).apply()

    var polishProvider: PolishProvider
        get() = runCatching {
            PolishProvider.valueOf(
                plain.getString(KEY_PP, null) ?: PolishProvider.ANTHROPIC.name
            )
        }.getOrDefault(PolishProvider.ANTHROPIC)
        set(v) = plain.edit().putString(KEY_PP, v.name).apply()

    // ---- Model overrides (blank = provider default) ----------------------

    var transcribeModel: String
        get() = plain.getString(KEY_TMODEL, "").orEmpty()
        set(v) = plain.edit().putString(KEY_TMODEL, v.trim()).apply()

    var polishModel: String
        get() = plain.getString(KEY_PMODEL, "").orEmpty()
        set(v) = plain.edit().putString(KEY_PMODEL, v.trim()).apply()

    // ---- Behaviour -------------------------------------------------------

    /** Run the LLM polish pass automatically after each transcription. */
    var autoPolish: Boolean
        get() = plain.getBoolean(KEY_AUTOPOLISH, true)
        set(v) = plain.edit().putBoolean(KEY_AUTOPOLISH, v).apply()

    /** Spoken language hint (ISO-639-1), blank = auto-detect. */
    var languageHint: String
        get() = plain.getString(KEY_LANG, "").orEmpty()
        set(v) = plain.edit().putString(KEY_LANG, v.trim()).apply()

    /**
     * Words/names the transcriber habitually gets wrong. Sent as a biasing
     * prompt (OpenAI) or context_bias list (Mistral).
     */
    var vocabulary: String
        get() = plain.getString(KEY_VOCAB, "").orEmpty()
        set(v) = plain.edit().putString(KEY_VOCAB, v).apply()

    fun vocabularyTerms(): List<String> =
        vocabulary.split(',', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    private companion object {
        const val KEY_ANTHROPIC = "anthropic_key"
        const val KEY_OPENAI = "openai_key"
        const val KEY_MISTRAL = "mistral_key"
        const val KEY_TP = "transcribe_provider"
        const val KEY_PP = "polish_provider"
        const val KEY_TMODEL = "transcribe_model"
        const val KEY_PMODEL = "polish_model"
        const val KEY_AUTOPOLISH = "auto_polish"
        const val KEY_LANG = "language_hint"
        const val KEY_VOCAB = "vocabulary"
    }
}
