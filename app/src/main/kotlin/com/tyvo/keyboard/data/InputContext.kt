package com.tyvo.keyboard.data

import android.text.InputType
import android.view.inputmethod.EditorInfo

/**
 * What kind of field the keyboard is in front of.
 *
 * Voice does not fit every field: dictating into a number pad or a password
 * box is slower than typing. The IME classifies the field in
 * `onStartInputView` and adapts -- see [behaviorFor].
 */
enum class InputContext {
    /** Ordinary prose. Voice is the whole point of the keyboard. */
    TEXT,

    /** Number, phone and date/time classes: digits, not sentences. */
    NUMERIC,

    /** A search box: the action is Search, not the usual text actions. */
    SEARCH,

    /** A password field, whatever flavour of password variation. */
    PASSWORD,

    /** A web address field. */
    URI,
    ;

    companion object {
        /**
         * Classifies a field from its [inputType] and [imeOptions].
         *
         * Takes primitives rather than `EditorInfo` so the classification is
         * plain unit-testable. Precedence: a numeric *class* wins over
         * everything (a phone field is a phone field even with a search
         * action), then password/URI *variations* (a password box is a
         * password box even with a search action), then the search action,
         * then anything else is text.
         */
        fun of(inputType: Int, imeOptions: Int): InputContext {
            val cls = inputType and InputType.TYPE_MASK_CLASS
            val variation = inputType and InputType.TYPE_MASK_VARIATION
            return when {
                cls == InputType.TYPE_CLASS_NUMBER ||
                    cls == InputType.TYPE_CLASS_PHONE ||
                    cls == InputType.TYPE_CLASS_DATETIME -> NUMERIC

                variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD -> PASSWORD

                variation == InputType.TYPE_TEXT_VARIATION_URI -> URI

                imeOptions and EditorInfo.IME_MASK_ACTION == EditorInfo.IME_ACTION_SEARCH -> SEARCH

                else -> TEXT
            }
        }

        /**
         * What the keyboard should do in [context], given the user's choice
         * for each non-text context. Text fields always dictate: that is the
         * product, and there is no setting for it.
         */
        fun behaviorFor(
            context: InputContext,
            numeric: ContextBehavior,
            search: ContextBehavior,
            password: ContextBehavior,
            uri: ContextBehavior,
        ): ContextBehavior = when (context) {
            InputContext.TEXT -> ContextBehavior.VOICE
            InputContext.NUMERIC -> numeric
            InputContext.SEARCH -> search
            InputContext.PASSWORD -> password
            InputContext.URI -> uri
        }
    }
}

/**
 * What the keyboard does in a field where voice does not fit.
 *
 * [FALLBACK] is the default everywhere: a yielded IME is no longer active
 * and cannot switch itself back on the next field, which strands the user.
 */
enum class ContextBehavior(val label: String) {
    /** Dictate as usual. */
    VOICE("Voice"),

    /** Show a built-in pad: a number pad for numeric fields, text keys otherwise. */
    FALLBACK("Keys"),

    /** Hand the field straight back to the previous keyboard. */
    YIELD("Switch away"),
}
