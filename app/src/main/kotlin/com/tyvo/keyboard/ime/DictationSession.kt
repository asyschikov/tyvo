package com.tyvo.keyboard.ime

/**
 * What the keyboard is doing right now. The UI renders directly off this.
 */
sealed interface UiState {
    /**
     * Ready, nothing in flight. [lastError] shows a dismissed-on-next-action
     * note, and [canRetry] offers another go at a recording that failed.
     */
    data class Idle(
        val lastError: String? = null,
        val canRetry: Boolean = false,
    ) : UiState

    data class Recording(val elapsedMs: Long) : UiState

    /** [step] is shown verbatim: "Transcribing...", "Polishing...". */
    data class Working(val step: String) : UiState

    /**
     * Text has been committed and can still be acted on. This is the state
     * that makes the keyboard interactive rather than fire-and-forget.
     *
     * [activeAction] is the id of the quick action currently applied, so the
     * strip can show which variant you are looking at.
     */
    data class Review(
        val text: String,
        val canUndo: Boolean,
        val canUnpolish: Boolean,
        val isPolished: Boolean,
        val activeAction: String? = null,
        val busy: Boolean = false,
        val note: String? = null,
    ) : UiState
}

/**
 * The text the keyboard has put into the field.
 *
 * The important idea here is the *base*: the text every quick action
 * transforms. Actions never chain -- asking for "formal" and then "shorter"
 * gives you a short version of the base, not a short version of the formal
 * version. Chaining rewrites compounds the model's drift, and after three
 * taps you are editing something several steps removed from what you said,
 * with no way back.
 *
 * So the session holds at most two strings: the base, and the one variant
 * currently derived from it.
 *
 * The base starts as the polished dictation, because that is what the user
 * sees and thinks of as "their text". [unpolish] swaps it for the raw
 * transcript, which is lossy on purpose: the polished version is discarded,
 * and [repolish] produces a fresh one rather than restoring the old.
 */
class DictationSession {

    /** The text every action transforms. */
    var base: String = ""
        private set

    /** The variant currently in the field, or null when the base is showing. */
    private var variant: String? = null

    /** The untouched transcript, kept so polish can be undone. */
    private var raw: String = ""

    /** What was actually said, before any clean-up. */
    val rawTranscript: String get() = raw

    /** Id of the action that produced [variant]. */
    var activeAction: String? = null
        private set

    /** Whether [base] is a polished version rather than the raw transcript. */
    var isPolished: Boolean = false
        private set

    /** Text currently in the field. */
    val current: String get() = variant ?: base

    val isActive: Boolean get() = current.isNotEmpty()

    /** An action is applied, so there is something to walk back to. */
    val canUndo: Boolean get() = variant != null

    /** The base is polished and a raw transcript exists to fall back to. */
    val canUnpolish: Boolean get() = isPolished && raw.isNotEmpty() && raw != base

    /** Starts a fresh session with a newly dictated raw transcript. */
    fun begin(transcript: String) {
        raw = transcript
        base = transcript
        variant = null
        activeAction = null
        isPolished = false
    }

    /**
     * Promotes a polished version to be the new base. Called both for the
     * automatic pass after dictation and for an explicit re-polish.
     */
    fun setPolished(text: String) {
        base = text
        variant = null
        activeAction = null
        isPolished = true
    }

    /**
     * Records the result of a quick action. Replaces any previous variant --
     * actions are alternatives to each other, not a pipeline.
     */
    fun setVariant(text: String, actionId: String?) {
        variant = if (text == base) null else text
        activeAction = if (variant == null) null else actionId
    }

    /** Drops the current action, returning to the base. */
    fun undo(): String? {
        if (variant == null) return null
        variant = null
        activeAction = null
        return base
    }

    /**
     * Discards the polished version and makes the raw transcript the base.
     *
     * Lossy by design: the polished text is gone, and a later re-polish
     * produces a new one rather than restoring this one.
     */
    fun unpolish(): String? {
        if (!canUnpolish) return null
        base = raw
        variant = null
        activeAction = null
        isPolished = false
        return base
    }

    /** Session is over -- the user typed, moved fields, or dismissed it. */
    fun clear() {
        raw = ""
        base = ""
        variant = null
        activeAction = null
        isPolished = false
    }
}
