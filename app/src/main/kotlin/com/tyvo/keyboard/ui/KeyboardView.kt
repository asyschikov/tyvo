package com.tyvo.keyboard.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.WindowInsetsCompat
import com.tyvo.keyboard.actions.QuickAction
import com.tyvo.keyboard.actions.QuickActions
import com.tyvo.keyboard.ime.UiState

/**
 * The keyboard surface.
 *
 * Three rows: a status line, the action strip (which changes with state), and
 * the control row with the mic. Built in code rather than XML so the whole
 * layout stays readable in one place.
 */
@SuppressLint("ViewConstructor")
class KeyboardView(context: Context) : LinearLayout(context) {

    var onMicPressed: () -> Unit = {}
    var onAction: (QuickAction) -> Unit = {}
    var onUndo: () -> Unit = {}
    var onAccept: () -> Unit = {}
    var onRepolish: () -> Unit = {}
    var onUnpolish: () -> Unit = {}
    var onRetry: () -> Unit = {}
    var onOpenSettings: () -> Unit = {}
    var onSwitchKeyboard: () -> Unit = {}
    var onBackspace: () -> Unit = {}
    var onNewline: () -> Unit = {}
    var onCustomInstruction: (String) -> Unit = {}

    private val status: TextView
    private val waveform: WaveformView
    private val actionStrip: LinearLayout
    private val actionScroll: HorizontalScrollView
    private val micButton: TextView
    private val undoButton: TextView
    private val doneButton: TextView
    private val customInput: EditText
    private val customRow: LinearLayout

    private var micEnabled = true

    init {
        orientation = VERTICAL
        setBackgroundColor(BG)
        val pad = dp(8)
        setPadding(pad, dp(6), pad, dp(10))

        // Keep the control row clear of the gesture-nav pill.
        setOnApplyWindowInsetsListener { v, insets ->
            val bars = WindowInsetsCompat.toWindowInsetsCompat(insets)
                .getInsets(WindowInsetsCompat.Type.navigationBars())
            v.setPadding(pad, dp(6), pad, dp(10) + bars.bottom)
            insets
        }

        // --- status line -------------------------------------------------
        status = TextView(context).apply {
            setTextColor(FG_DIM)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(6), 0, dp(6), 0)
        }
        addView(status, lp(MATCH, dp(34)))

        // --- waveform ----------------------------------------------------
        waveform = WaveformView(context)
        addView(waveform, lp(MATCH, dp(28)).also { it.bottomMargin = dp(4) })

        // --- custom instruction row (hidden until asked for) -------------
        customRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            visibility = GONE
        }
        customInput = EditText(context).apply {
            hint = "Tell Tyvo how to change it…"
            setHintTextColor(FG_FAINT)
            setTextColor(FG)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setBackgroundColor(SURFACE)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            maxLines = 2
            isSingleLine = true
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, _, _ -> submitCustom(); true }
        }
        customRow.addView(customInput, LayoutParams(0, dp(44), 1f))
        val goButton = pill("Go", accent = true) { submitCustom() }
        customRow.addView(goButton, lp(WRAP, dp(44)).also { it.leftMargin = dp(6) })
        addView(customRow, lp(MATCH, WRAP).also { it.bottomMargin = dp(6) })

        // --- action strip -------------------------------------------------
        actionStrip = LinearLayout(context).apply { orientation = HORIZONTAL }
        actionScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(actionStrip)
        }
        addView(actionScroll, lp(MATCH, dp(46)).also { it.bottomMargin = dp(8) })

        // --- control row --------------------------------------------------
        val controls = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val globe = pill("⌨", accent = false) { onSwitchKeyboard() }
        controls.addView(globe, lp(dp(52), dp(56)))

        undoButton = pill("Undo", accent = false) { onUndo() }
        controls.addView(undoButton, lp(WRAP, dp(56)).also { it.leftMargin = dp(6) })

        micButton = TextView(context).apply {
            text = "Hold to talk"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            background = roundedDrawable(ACCENT, dp(14).toFloat())
            isClickable = true
            setOnClickListener { if (micEnabled) onMicPressed() }
        }
        controls.addView(micButton, LayoutParams(0, dp(56), 1f).also {
            it.leftMargin = dp(6); it.rightMargin = dp(6)
        })

        doneButton = pill("Done", accent = false) { onAccept() }
        controls.addView(doneButton, lp(WRAP, dp(56)))

        val back = pill("⌫", accent = false) { onBackspace() }
        controls.addView(back, lp(dp(52), dp(56)).also { it.leftMargin = dp(6) })

        addView(controls, lp(MATCH, WRAP))
    }

    fun setMicEnabled(enabled: Boolean) {
        micEnabled = enabled
        micButton.alpha = if (enabled) 1f else 0.5f
    }

    fun setAmplitude(a: Float) = waveform.push(a)

    /** Rebuilds the surface for [state]. */
    fun render(state: UiState) {
        when (state) {
            is UiState.Idle -> {
                status.text = state.lastError ?: "Tap the mic and speak."
                status.setTextColor(if (state.lastError != null) WARN else FG_DIM)
                waveform.visibility = GONE
                micButton.text = "Speak"
                micButton.background = roundedDrawable(ACCENT, dp(14).toFloat())
                undoButton.visibility = GONE
                doneButton.visibility = GONE
                customRow.visibility = GONE
                showIdleActions(state.canRetry)
            }

            is UiState.Recording -> {
                status.text = "Listening   ${fmt(state.elapsedMs)}"
                status.setTextColor(REC)
                waveform.visibility = VISIBLE
                micButton.text = "Done"
                micButton.background = roundedDrawable(REC, dp(14).toFloat())
                undoButton.visibility = GONE
                doneButton.visibility = GONE
                customRow.visibility = GONE
                actionStrip.removeAllViews()
            }

            is UiState.Working -> {
                status.text = "${state.step}…"
                status.setTextColor(FG_DIM)
                waveform.visibility = GONE
                micButton.text = "…"
                micButton.background = roundedDrawable(ACCENT_DIM, dp(14).toFloat())
                undoButton.visibility = GONE
                doneButton.visibility = GONE
                customRow.visibility = GONE
                actionStrip.removeAllViews()
            }

            is UiState.Review -> {
                status.text = when {
                    state.note != null && state.busy -> "${state.note}…"
                    state.note != null -> state.note
                    else -> preview(state.text)
                }
                status.setTextColor(
                    if (state.note != null && !state.busy) WARN else FG_DIM
                )
                waveform.visibility = GONE
                micButton.text = "Speak"
                micButton.background = roundedDrawable(ACCENT, dp(14).toFloat())
                undoButton.visibility = if (state.canUndo) VISIBLE else GONE
                doneButton.visibility = VISIBLE
                customRow.visibility = VISIBLE
                showReviewActions(state, enabled = !state.busy)
            }
        }
    }

    // ---- action strips --------------------------------------------------

    private fun showIdleActions(canRetry: Boolean) {
        actionStrip.removeAllViews()
        // Retry leads: a saved recording is unfinished work, and burying it
        // behind the app is how a dictation quietly gets forgotten.
        if (canRetry) {
            actionStrip.addView(chip("↻ Retry", selected = true) { onRetry() })
        }
        actionStrip.addView(chip("Settings") { onOpenSettings() })
        actionStrip.addView(chip("↵ Newline") { onNewline() })
    }

    /**
     * The action strip.
     *
     * The polish control leads because it changes what everything else acts
     * on: quick actions transform the base text, and unpolishing swaps the
     * base from the cleaned-up version to what was actually said.
     */
    private fun showReviewActions(state: UiState.Review, enabled: Boolean) {
        actionStrip.removeAllViews()

        if (state.canUnpolish) {
            actionStrip.addView(chip("↩ Undo clean-up", enabled) { onUnpolish() })
        }
        actionStrip.addView(
            chip(if (state.isPolished) "↻ Re-clean" else "✦ Clean up", enabled) { onRepolish() }
        )
        QuickActions.ALL.forEach { action ->
            val on = action.id == state.activeAction
            actionStrip.addView(chip(action.label, enabled, selected = on) { onAction(action) })
        }
        actionScroll.scrollTo(0, 0)
    }

    private fun submitCustom() {
        val text = customInput.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        customInput.setText("")
        onCustomInstruction(text)
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
                    selected -> Color.WHITE
                    enabled -> FG
                    else -> FG_FAINT
                }
            )
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(14), 0, dp(14), 0)
            background = roundedDrawable(if (selected) ACCENT else SURFACE, dp(11).toFloat())
            isClickable = enabled
            alpha = if (enabled) 1f else 0.55f
            setOnClickListener { if (enabled) onTap() }
            layoutParams = lp(WRAP, dp(38)).also { it.rightMargin = dp(6) }
            height = dp(38)
        }

    private fun pill(label: String, accent: Boolean, onTap: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(if (accent) Color.WHITE else FG)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(14), 0, dp(14), 0)
            background = roundedDrawable(if (accent) ACCENT else SURFACE, dp(13).toFloat())
            isClickable = true
            setOnClickListener { onTap() }
        }

    private fun roundedDrawable(color: Int, radius: Float) =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
        }

    private fun preview(text: String): String {
        val flat = text.replace('\n', ' ').trim()
        return if (flat.length <= 64) flat else flat.take(63) + "…"
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

    private fun lp(w: Int, h: Int) = LayoutParams(w, h)

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        val BG = Color.parseColor("#16181D")
        val SURFACE = Color.parseColor("#262A32")
        val FG = Color.parseColor("#E8EAED")
        val FG_DIM = Color.parseColor("#A8AEBB")
        val FG_FAINT = Color.parseColor("#6B7280")
        val ACCENT = Color.parseColor("#4C7DF0")
        val ACCENT_DIM = Color.parseColor("#33507F")
        val REC = Color.parseColor("#E5484D")
        val WARN = Color.parseColor("#F5A524")
    }
}
