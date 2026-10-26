package com.tyvo.keyboard.bubble

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.tyvo.keyboard.actions.QuickAction
import com.tyvo.keyboard.actions.QuickActions
import com.tyvo.keyboard.ime.UiState
import com.tyvo.keyboard.ui.Palette

/**
 * The floating mic's edit panel: the keyboard's review strip, on a card.
 *
 * Opened by holding the bubble after a dictation, so the plain flow stays a
 * single tap. Same controls, same order as the keyboard, so nothing has to be
 * learned twice.
 */
@SuppressLint("ViewConstructor")
class BubblePanel(context: Context) : LinearLayout(context) {

    private val c = Palette.of(context)

    var onAction: (QuickAction) -> Unit = {}
    /** Read on every render, so edits in settings show up without a restart. */
    var actions: () -> List<QuickAction> = { QuickActions.ALL }
    var onUndo: () -> Unit = {}
    var onAccept: () -> Unit = {}
    var onDelete: () -> Unit = {}
    var onRepolish: () -> Unit = {}
    var onUnpolish: () -> Unit = {}
    /** A touch landed outside the card. */
    var onDismiss: () -> Unit = {}

    private val status: TextView
    private val actionStrip: LinearLayout
    private val actionScroll: HorizontalScrollView
    private val undoButton: TextView

    init {
        orientation = VERTICAL
        background = rounded(c.bg, dp(18).toFloat())
        elevation = dp(8).toFloat()
        setPadding(dp(10), dp(10), dp(10), dp(10))

        status = TextView(context).apply {
            setTextColor(c.fgDim)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(6), 0, dp(6), dp(8))
        }
        addView(status, LayoutParams(MATCH, WRAP))

        actionStrip = LinearLayout(context).apply { orientation = HORIZONTAL }
        actionScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(actionStrip)
        }
        addView(actionScroll, LayoutParams(MATCH, dp(38)).also { it.bottomMargin = dp(8) })

        val controls = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        undoButton = pill("Undo") { onUndo() }
        controls.addView(undoButton, LayoutParams(WRAP, dp(44)))
        controls.addView(View(context), LayoutParams(0, 1, 1f))
        controls.addView(
            pill("Delete") { onDelete() },
            LayoutParams(WRAP, dp(44)).also { it.rightMargin = dp(6) },
        )
        controls.addView(pill("Done") { onAccept() }, LayoutParams(WRAP, dp(44)))
        addView(controls, LayoutParams(MATCH, WRAP))
    }

    fun render(state: UiState.Review) {
        val note = state.note?.let { if (state.busy) "$it…" else it }
        status.visibility = if (note.isNullOrBlank()) GONE else VISIBLE
        status.text = note
        status.setTextColor(
            when {
                state.busy -> c.fgDim
                state.failed -> c.rec
                else -> c.warn
            }
        )
        undoButton.visibility = if (state.canUndo) VISIBLE else GONE

        val enabled = !state.busy
        actionStrip.removeAllViews()
        if (state.canUnpolish) {
            actionStrip.addView(chip("↩ Undo clean-up", enabled) { onUnpolish() })
        }
        actionStrip.addView(
            chip(if (state.isPolished) "↻ Re-clean" else "✦ Clean up", enabled) { onRepolish() }
        )
        actions().forEach { action ->
            val on = action.id == state.activeAction
            actionStrip.addView(chip(action.label, enabled, selected = on) { onAction(action) })
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        // Delivered because the window watches for outside touches. The
        // touch itself still goes to whatever was tapped; we only close.
        if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            onDismiss()
            return true
        }
        return super.dispatchTouchEvent(e)
    }

    // ---- small builders --------------------------------------------------

    private fun chip(
        label: String,
        enabled: Boolean = true,
        selected: Boolean = false,
        onTap: () -> Unit,
    ): View =
        TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(
                when {
                    selected -> c.onAccent
                    enabled -> c.fg
                    else -> c.fgFaint
                }
            )
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(14), 0, dp(14), 0)
            background = rounded(if (selected) c.accent else c.surface, dp(11).toFloat())
            isClickable = enabled
            alpha = if (enabled) 1f else 0.55f
            setOnClickListener { if (enabled) onTap() }
            layoutParams = LayoutParams(WRAP, dp(38)).also { it.rightMargin = dp(6) }
        }

    private fun pill(label: String, onTap: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(c.fg)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(16), 0, dp(16), 0)
            background = rounded(c.surface, dp(13).toFloat())
            isClickable = true
            setOnClickListener { onTap() }
        }

    private fun rounded(color: Int, radius: Float) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
        }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
