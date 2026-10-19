package com.tyvo.keyboard.bubble

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Writes into another app's text field through accessibility.
 *
 * Rewrites the whole field with SET_TEXT, because that is the one action
 * that edits text in place without touching the clipboard. Fields that refuse
 * it (some WebViews) get the fallback: select the span, paste over it.
 */
object FieldWriter {

    /**
     * The field's real text, "" while it is only showing its hint, or null
     * if it cannot be read at all (a password field).
     */
    fun textOf(node: AccessibilityNodeInfo): String? {
        if (node.isPassword) return null
        if (node.isShowingHintText) return ""
        val text = node.text?.toString().orEmpty()
        // Some apps report the hint as the text without flagging it.
        if (text.isNotEmpty() && text == node.hintText?.toString() &&
            node.textSelectionStart <= 0
        ) return ""
        return text
    }

    /** Inserts [text] at the field's cursor. Null if nothing was written. */
    fun insert(context: Context, node: AccessibilityNodeInfo, text: String): Placement? {
        val current = textOf(node) ?: return null
        val edit = Splice.insert(current, node.textSelectionStart, node.textSelectionEnd, text)
        return if (write(context, node, edit)) edit.placement else null
    }

    /**
     * Replaces our earlier insertion [at] with [text]. Null if the field no
     * longer holds it, or the write failed.
     */
    fun replace(
        context: Context,
        node: AccessibilityNodeInfo,
        at: Placement,
        text: String,
    ): Placement? {
        val current = textOf(node) ?: return null
        val edit = Splice.replace(current, at, text) ?: return null
        return if (write(context, node, edit)) edit.placement else null
    }

    /** Takes our insertion [at] out of the field. False if it is no longer there. */
    fun remove(context: Context, node: AccessibilityNodeInfo, at: Placement): Boolean {
        val current = textOf(node) ?: return false
        val edit = Splice.remove(current, at) ?: return false
        return write(context, node, edit)
    }

    private fun write(context: Context, node: AccessibilityNodeInfo, edit: Splice.Edit): Boolean {
        val cursor = edit.placement.end
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, edit.full)
        }
        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            // SET_TEXT leaves the cursor wherever the app decides, usually
            // the end. Put it after our text, where typing would have left it.
            select(node, cursor, cursor)
            return true
        }

        // The paste leaves our text on the clipboard. Putting the previous
        // clip back is not an option: Android does not let a background
        // service read the clipboard.
        if (!select(node, edit.from, edit.to)) return false
        // Nothing to paste over a deletion, so cut the span instead.
        if (edit.placement.text.isEmpty()) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CUT)
        }
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Tyvo", edit.placement.text))
        return node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }

    private fun select(node: AccessibilityNodeInfo, start: Int, end: Int): Boolean {
        val args = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
    }
}
