package com.tyvo.keyboard.data

import android.text.InputType
import android.view.inputmethod.EditorInfo

/**
 * What kind of field the keyboard is in front of.
 *
 * Voice does not fit every field: dictating into a number pad or a password
 * box is slower than typing. The IME classifies the field in
 * `onStartInputView` and hands every non-[TEXT] field back to the previous
 * keyboard, rather than growing keys of its own.
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
    }
}
