package com.tyvo.keyboard.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.tyvo.keyboard.actions.QuickAction
import com.tyvo.keyboard.actions.QuickActions
import com.tyvo.keyboard.polish.Correction
import com.tyvo.keyboard.polish.Prompts
import org.json.JSONArray
import org.json.JSONObject

/**
 * The AI provider backing the whole keyboard.
 *
 * One provider handles both transcription and polish. Mixing providers across
 * the two stages would mean holding two API keys to do one job, which is not
 * a trade worth offering by default.
 */
enum class Provider(
    val label: String,
    /** False until someone has actually dictated through it end to end. */
    val verified: Boolean = true,
) {
    // Declaration order is display order: every provider list renders from
    // Provider.entries, so Mistral leads because it is the default and the
    // best-tested. Persistence is by name, so reordering is safe.
    MISTRAL("Mistral"),
    OPENAI("OpenAI"),
    XAI("xAI (Grok)"),
    GEMINI("Google Gemini");
}

/** How big the floating mic is. [dp] is the circle's diameter. */
enum class BubbleSize(val label: String, val dp: Int) {
    SMALL("Small", 36),
    MEDIUM("Medium", 44),
    LARGE("Large", 52),
}

/**
 * How see-through the floating mic is while resting. Recording and working
 * are always fully opaque: that is when it has something to say.
 */
enum class BubbleOpacity(val label: String, val alpha: Float) {
    LOW("Low", 0.4f),
    MEDIUM("Medium", 0.7f),
    FULL("Full", 1f),
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
        Provider.XAI -> KEY_XAI
        Provider.GEMINI -> KEY_GEMINI
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

    /**
     * Throw away a fresh dictation under a few seconds that comes back with
     * no words -- a pocket tap or a mic opened by mistake -- without saving
     * it or saying anything. Off keeps the usual "Nothing heard. Saved".
     */
    var discardShortSilence: Boolean
        get() = plain.getBoolean(KEY_DISCARD_SHORT_SILENCE, true)
        set(v) = plain.edit().putBoolean(KEY_DISCARD_SHORT_SILENCE, v).apply()

    /**
     * Whether the welcome flow has been completed.
     *
     * Drives which screen the app opens on: welcome the first time, history
     * thereafter. Set only when the user finishes onboarding, so quitting
     * halfway brings them back to it.
     */
    var hasOnboarded: Boolean
        get() = plain.getBoolean(KEY_ONBOARDED, false)
        set(v) = plain.edit().putBoolean(KEY_ONBOARDED, v).apply()

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

    /** The user's own clean-up rules, added to the prompt as its last step. */
    var cleanupRules: String
        get() = plain.getString(KEY_CLEANUP_RULES, "").orEmpty()
        set(v) = plain.edit().putString(KEY_CLEANUP_RULES, v).apply()

    /**
     * A clean-up prompt the user wrote in full, or blank for the built one.
     * While set, the corrections and rules above it are not used.
     */
    var cleanupPrompt: String
        get() = plain.getString(KEY_CLEANUP_PROMPT, "").orEmpty()
        set(v) = plain.edit().putString(KEY_CLEANUP_PROMPT, v).apply()

    /**
     * The built prompt as it stood when the user saved their own. When the
     * app later ships a different one, the two no longer match and the
     * editor says so -- an edited prompt otherwise misses every improvement.
     */
    var cleanupPromptBase: String
        get() = plain.getString(KEY_CLEANUP_BASE, "").orEmpty()
        set(v) = plain.edit().putString(KEY_CLEANUP_BASE, v).apply()

    /** What the clean-up prompt would be without the user's full override. */
    fun builtCleanupPrompt(): String = Prompts.cleanUp(corrections(), cleanupRules)

    /** The system prompt clean-up actually sends. */
    fun cleanupSystemPrompt(): String = cleanupPrompt.ifBlank { builtCleanupPrompt() }

    fun saveCleanupPrompt(text: String) {
        val built = builtCleanupPrompt()
        // Saving the built prompt unchanged is not an override.
        if (text.isBlank() || text.trim() == built.trim()) {
            resetCleanupPrompt()
        } else {
            cleanupPrompt = text
            cleanupPromptBase = built
        }
    }

    fun resetCleanupPrompt() {
        cleanupPrompt = ""
        cleanupPromptBase = ""
    }

    /** True when the user's prompt was written against an older built one. */
    val cleanupDefaultChanged: Boolean
        get() = cleanupPrompt.isNotBlank() && cleanupPromptBase != builtCleanupPrompt()

    /** Built-in quick actions whose instruction the user rewrote, by id. */
    var actionEdits: Map<String, String>
        get() = runCatching {
            val o = JSONObject(plain.getString(KEY_ACTION_EDITS, null) ?: "{}")
            o.keys().asSequence().associateWith { o.getString(it) }
        }.getOrDefault(emptyMap())
        set(v) = plain.edit().putString(KEY_ACTION_EDITS, JSONObject(v).toString()).apply()

    /** Quick actions the user made, in the order they made them. */
    var customActions: List<QuickAction>
        get() = runCatching {
            val a = JSONArray(plain.getString(KEY_CUSTOM_ACTIONS, null) ?: "[]")
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                QuickActions.custom(o.getString("id"), o.getString("label"), o.getString("instruction"))
            }
        }.getOrDefault(emptyList())
        set(v) = plain.edit().putString(
            KEY_CUSTOM_ACTIONS,
            JSONArray(v.map {
                JSONObject().put("id", it.id).put("label", it.label).put("instruction", it.instruction)
            }).toString(),
        ).apply()

    /** The action bar: built-ins as the user edited them, then their own. */
    fun quickActions(): List<QuickAction> = QuickActions.merged(actionEdits, customActions)

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

    // ---- Input contexts --------------------------------------------------
    //
    // What the keyboard does in fields where voice does not fit, per
    // context. FALLBACK by default: a yielded IME cannot switch itself back
    // on the next field, which strands the user.

    var numericBehavior: ContextBehavior
        get() = behaviorPref(KEY_CTX_NUMERIC, ContextBehavior.FALLBACK)
        set(v) = plain.edit().putString(KEY_CTX_NUMERIC, v.name).apply()

    var searchBehavior: ContextBehavior
        get() = behaviorPref(KEY_CTX_SEARCH, ContextBehavior.FALLBACK)
        set(v) = plain.edit().putString(KEY_CTX_SEARCH, v.name).apply()

    var passwordBehavior: ContextBehavior
        get() = behaviorPref(KEY_CTX_PASSWORD, ContextBehavior.FALLBACK)
        set(v) = plain.edit().putString(KEY_CTX_PASSWORD, v.name).apply()

    var uriBehavior: ContextBehavior
        get() = behaviorPref(KEY_CTX_URI, ContextBehavior.FALLBACK)
        set(v) = plain.edit().putString(KEY_CTX_URI, v.name).apply()

    private fun behaviorPref(key: String, default: ContextBehavior) = runCatching {
        ContextBehavior.valueOf(plain.getString(key, null) ?: default.name)
    }.getOrDefault(default)

    // ---- Floating mic ----------------------------------------------------

    /**
     * Where the user left the floating mic: its top edge in screen pixels, or
     * -1 if it has never been moved, which means "just above the keyboard".
     */
    var bubbleY: Int
        get() = plain.getInt(KEY_BUBBLE_Y, -1)
        set(v) = plain.edit().putInt(KEY_BUBBLE_Y, v).apply()

    /** Which edge the floating mic is docked to. Right by default, by the thumb. */
    var bubbleOnLeft: Boolean
        get() = plain.getBoolean(KEY_BUBBLE_LEFT, false)
        set(v) = plain.edit().putBoolean(KEY_BUBBLE_LEFT, v).apply()

    var bubbleSize: BubbleSize
        get() = runCatching {
            BubbleSize.valueOf(plain.getString(KEY_BUBBLE_SIZE, null) ?: BubbleSize.LARGE.name)
        }.getOrDefault(BubbleSize.LARGE)
        set(v) = plain.edit().putString(KEY_BUBBLE_SIZE, v.name).apply()

    var bubbleOpacity: BubbleOpacity
        get() = runCatching {
            BubbleOpacity.valueOf(plain.getString(KEY_BUBBLE_OPACITY, null) ?: BubbleOpacity.FULL.name)
        }.getOrDefault(BubbleOpacity.FULL)
        set(v) = plain.edit().putString(KEY_BUBBLE_OPACITY, v.name).apply()

    private companion object {
        const val KEY_PROVIDER = "provider"
        const val KEY_OPENAI = "openai_key"
        const val KEY_MISTRAL = "mistral_key"
        const val KEY_XAI = "xai_key"
        const val KEY_GEMINI = "gemini_key"
        const val KEY_AUTOPOLISH = "auto_polish"
        const val KEY_POLISH_ON = "polish_enabled"
        const val KEY_NOTIFY_FAIL = "notify_failures"
        const val KEY_TOAST_FAIL = "toast_failures"
        const val KEY_KEEP_FAILED = "keep_failed_audio"
        const val KEY_DISCARD_SHORT_SILENCE = "discard_short_silence"
        const val KEY_CORRECTIONS = "enabled_corrections"
        const val KEY_CLEANUP_RULES = "cleanup_rules"
        const val KEY_CLEANUP_PROMPT = "cleanup_prompt"
        const val KEY_CLEANUP_BASE = "cleanup_prompt_base"
        const val KEY_ACTION_EDITS = "action_edits"
        const val KEY_CUSTOM_ACTIONS = "custom_actions"
        const val KEY_ONBOARDED = "has_onboarded"
        const val KEY_LANG = "language_hint"
        const val KEY_VOCAB = "vocabulary"
        const val KEY_BUBBLE_Y = "bubble_y"
        const val KEY_BUBBLE_LEFT = "bubble_left"
        const val KEY_BUBBLE_SIZE = "bubble_size"
        const val KEY_BUBBLE_OPACITY = "bubble_opacity"
        const val KEY_CTX_NUMERIC = "ctx_numeric"
        const val KEY_CTX_SEARCH = "ctx_search"
        const val KEY_CTX_PASSWORD = "ctx_password"
        const val KEY_CTX_URI = "ctx_uri"
    }
}
