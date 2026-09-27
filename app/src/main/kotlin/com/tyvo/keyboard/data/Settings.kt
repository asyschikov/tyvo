package com.tyvo.keyboard.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.tyvo.keyboard.polish.Correction

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

    /**
     * Post a notification when a dictation could not be transcribed.
     *
     * On by default: the recording is kept either way, but without the
     * notification a failure that happened while typing in another app is
     * easy to walk away from and forget.
     */
    var failureNotifications: Boolean
        get() = plain.getBoolean(KEY_NOTIFY_FAIL, true)
        set(v) = plain.edit().putBoolean(KEY_NOTIFY_FAIL, v).apply()

    /**
     * Keep audio for failed dictations past the usual 24 hours.
     *
     * Successful recordings still expire on schedule: their text is the copy
     * that matters. For a failure the audio is the only copy there is.
     */
    var keepFailedAudio: Boolean
        get() = plain.getBoolean(KEY_KEEP_FAILED, false)
        set(v) = plain.edit().putBoolean(KEY_KEEP_FAILED, v).apply()

    /** Show a brief toast when a dictation fails. */
    var failureToasts: Boolean
        get() = plain.getBoolean(KEY_TOAST_FAIL, true)
        set(v) = plain.edit().putBoolean(KEY_TOAST_FAIL, v).apply()

    /**
     * Which clean-up corrections are enabled, by id.
     *
     * Stored as an explicit set rather than a flag per correction so that
     * adding one later does not silently switch it on for existing users --
     * an unset value means "never chosen", which falls back to the defaults.
     */
    var enabledCorrections: Set<String>
        get() = plain.getStringSet(KEY_CORRECTIONS, null)
            ?: Correction.defaults.map { it.id }.toSet()
        set(v) = plain.edit().putStringSet(KEY_CORRECTIONS, v).apply()

    fun corrections(): Set<Correction> =
        enabledCorrections.mapNotNull { Correction.byId(it) }.toSet()

    fun setCorrection(c: Correction, on: Boolean) {
        enabledCorrections = if (on) enabledCorrections + c.id else enabledCorrections - c.id
    }

    /**
     * The language sent to the transcriber, or blank to let it detect.
     *
     * One slot rather than a list because the transcription APIs accept
     * exactly one code. Offering several would only ever send the first,
     * which is a setting that lies about what it does.
     */
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
        const val KEY_NOTIFY_FAIL = "notify_failures"
        const val KEY_TOAST_FAIL = "toast_failures"
        const val KEY_KEEP_FAILED = "keep_failed_audio"
        const val KEY_CORRECTIONS = "enabled_corrections"
        const val KEY_LANG = "language_hint"
        const val KEY_VOCAB = "vocabulary"
    }
}
