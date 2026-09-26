package com.tyvo.keyboard.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * The AI provider backing the whole keyboard.
 *
 * One provider handles both transcription and polish. Mixing providers across
 * the two stages would mean holding two API keys to do one job, which is not
 * a trade worth offering by default.
 */
enum class Provider(val label: String) {
    OPENAI("OpenAI"),
    MISTRAL("Mistral");
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

    // ---- Provider --------------------------------------------------------

    var provider: Provider
        get() = runCatching {
            Provider.valueOf(plain.getString(KEY_PROVIDER, null) ?: Provider.MISTRAL.name)
        }.getOrDefault(Provider.MISTRAL)
        set(v) = plain.edit().putString(KEY_PROVIDER, v.name).apply()

    // ---- API keys --------------------------------------------------------
    //
    // Each provider keeps its own slot, so switching away and back never
    // costs you a key you already pasted in.

    fun key(p: Provider): String = secure.getString(keyName(p), "").orEmpty()

    fun setKey(p: Provider, value: String) {
        secure.edit().putString(keyName(p), value.trim()).apply()
    }

    /** Key for the currently selected provider. */
    val currentKey: String get() = key(provider)

    private fun keyName(p: Provider) = when (p) {
        Provider.OPENAI -> KEY_OPENAI
        Provider.MISTRAL -> KEY_MISTRAL
    }

    // ---- Models ----------------------------------------------------------
    //
    // Also stored per provider: a model id is only meaningful to the provider
    // it belongs to, and carrying one across a switch would send a Mistral
    // model name to OpenAI. Blank means "use the default".

    fun transcribeModel(p: Provider): String =
        plain.getString(modelName(p, "transcribe"), "").orEmpty()

    fun setTranscribeModel(p: Provider, value: String) {
        plain.edit().putString(modelName(p, "transcribe"), value.trim()).apply()
    }

    fun polishModel(p: Provider): String =
        plain.getString(modelName(p, "polish"), "").orEmpty()

    fun setPolishModel(p: Provider, value: String) {
        plain.edit().putString(modelName(p, "polish"), value.trim()).apply()
    }

    val currentTranscribeModel: String get() = transcribeModel(provider)
    val currentPolishModel: String get() = polishModel(provider)

    private fun modelName(p: Provider, stage: String) = "model_${stage}_${p.name}"

    // ---- Behaviour -------------------------------------------------------

    /** Run the LLM polish pass automatically after each transcription. */
    var autoPolish: Boolean
        get() = plain.getBoolean(KEY_AUTOPOLISH, true)
        set(v) = plain.edit().putBoolean(KEY_AUTOPOLISH, v).apply()

    /**
     * Whether the polish stage runs at all. Off means the raw transcript is
     * inserted and the quick actions are unavailable.
     */
    var polishEnabled: Boolean
        get() = plain.getBoolean(KEY_POLISH_ON, true)
        set(v) = plain.edit().putBoolean(KEY_POLISH_ON, v).apply()

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
        const val KEY_PROVIDER = "provider"
        const val KEY_OPENAI = "openai_key"
        const val KEY_MISTRAL = "mistral_key"
        const val KEY_AUTOPOLISH = "auto_polish"
        const val KEY_POLISH_ON = "polish_enabled"
        const val KEY_LANG = "language_hint"
        const val KEY_VOCAB = "vocabulary"
    }
}
