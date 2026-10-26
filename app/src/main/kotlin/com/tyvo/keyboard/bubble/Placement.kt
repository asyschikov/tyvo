package com.tyvo.keyboard.bubble

import com.tyvo.keyboard.ime.joinsWithSpace

/**
 * Where text the floating mic inserted sits in a field.
 *
 * The keyboard gets to edit through an InputConnection, which knows where the
 * cursor is. From outside the app all we can do is rewrite the field's whole
 * text, so we remember the exact span we put there, and later edits replace
 * that span and nothing else.
 *
 * [text] is exactly what was inserted, leading space included.
 */
data class Placement(val start: Int, val text: String) {
    val end: Int get() = start + text.length
}

/** The string arithmetic behind the floating mic's edits, kept free of Android. */
object Splice {

    /** A field's new full text, plus where our part of it sits. */
    data class Edit(val full: String, val placement: Placement, val from: Int, val to: Int)

    /**
     * Puts [text] over the selection [selStart]..[selEnd] of [current].
     *
     * A selection we cannot read (negative, as some fields report it) or that
     * points past the end means "append": the end of the field is where the
     * user was most likely typing.
     */
    fun insert(current: String, selStart: Int, selEnd: Int, text: String): Edit {
        val known = selStart >= 0 && selEnd >= 0 &&
            selStart <= current.length && selEnd <= current.length
        val from = if (known) minOf(selStart, selEnd) else current.length
        val to = if (known) maxOf(selStart, selEnd) else current.length
        val prev = current.getOrNull(from - 1)
        val piece = if (prev != null && joinsWithSpace(prev)) " $text" else text
        return Edit(
            full = current.substring(0, from) + piece + current.substring(to),
            placement = Placement(from, piece),
            from = from,
            to = to,
        )
    }

    /**
     * Swaps our earlier insertion for [text], or returns null if the field
     * no longer holds it where we left it -- the user edited around it, or the
     * app rewrote the field. Replacing anyway would overwrite their words.
     */
    fun replace(current: String, at: Placement, text: String): Edit? {
        if (!holds(current, at)) return null
        // Keep the space we joined with, so a rewrite does not glue itself
        // onto the previous word.
        val piece = at.text.takeWhile { it == ' ' } + text.trimStart()
        return Edit(
            full = current.substring(0, at.start) + piece + current.substring(at.end),
            placement = Placement(at.start, piece),
            from = at.start,
            to = at.end,
        )
    }

    /**
     * Takes our insertion out again, joining space and all, or null if the
     * field no longer holds it where we left it.
     */
    fun remove(current: String, at: Placement): Edit? {
        if (!holds(current, at)) return null
        return Edit(
            full = current.substring(0, at.start) + current.substring(at.end),
            placement = Placement(at.start, ""),
            from = at.start,
            to = at.end,
        )
    }

    private fun holds(current: String, at: Placement): Boolean =
        at.end <= current.length && current.substring(at.start, at.end) == at.text

    /**
     * Whether a field answered a request for the cursor at [length] by
     * putting it there. Kept apart from the accessibility calls so the rule
     * itself can be tested.
     */
    fun cursorConfirms(length: Int, selStart: Int, selEnd: Int): Boolean =
        selStart == length && selEnd == length
}
