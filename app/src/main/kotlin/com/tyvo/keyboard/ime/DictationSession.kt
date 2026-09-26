package com.tyvo.keyboard.ime

/**
 * What the keyboard is doing right now. The UI renders directly off this.
 */
sealed interface UiState {
    /** Ready, nothing in flight. [lastError] shows a dismissed-on-next-action note. */
    data class Idle(val lastError: String? = null) : UiState

    data class Recording(val elapsedMs: Long) : UiState

    /** [step] is shown verbatim: "Transcribing...", "Polishing...". */
    data class Working(val step: String) : UiState

    /**
     * Text has been committed and can still be acted on. This is the state
     * that makes the keyboard interactive rather than fire-and-forget.
     */
    data class Review(
        val text: String,
        val canUndo: Boolean,
        val busy: Boolean = false,
        val note: String? = null,
    ) : UiState
}

/**
 * The text the keyboard has put into the field, plus the history needed to
 * walk it back.
 *
 * Every transformation pushes the previous value, so "Undo" is always one tap
 * and a chain of rewrites can be unwound one step at a time.
 */
class DictationSession {

    /** Text currently in the field that belongs to this session. */
    var current: String = ""
        private set

    private val history = ArrayDeque<String>()

    val canUndo: Boolean get() = history.isNotEmpty()
    val isActive: Boolean get() = current.isNotEmpty()

    /** Starts a fresh session with newly dictated text. */
    fun begin(text: String) {
        history.clear()
        current = text
    }

    /** Records a transformation, keeping the previous value for undo. */
    fun advance(text: String) {
        if (text == current) return
        history.addLast(current)
        if (history.size > MAX_HISTORY) history.removeFirst()
        current = text
    }

    /** Steps back one transformation. Returns the restored text, or null. */
    fun undo(): String? {
        val prev = history.removeLastOrNull() ?: return null
        current = prev
        return prev
    }

    /** Session is over -- the user typed, moved fields, or dismissed it. */
    fun clear() {
        history.clear()
        current = ""
    }

    private companion object {
        const val MAX_HISTORY = 20
    }
}
