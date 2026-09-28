/*
 * Tyvo -- a voice keyboard that writes what you meant.
 * Copyright (C) 2026 Andrey Syschikov
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
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
    var onBackspace: (byWord: Boolean) -> Unit = {}
    var onNewline: () -> Unit = {}
    var onSpace: () -> Unit = {}
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
    private val controls: LinearLayout

    /** True while the mic button is standing in as the setup prompt. */
    private var micIsSetupPrompt = false

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
        controls = LinearLayout(context).apply {
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
            // Doubles as the setup prompt when the keyboard is not yet
            // usable, so tapping it opens settings rather than the mic.
            setOnClickListener {
                if (micIsSetupPrompt) onOpenSettings() else onMicPressed()
            }
        }
        controls.addView(micButton, LayoutParams(0, dp(56), 1f).also {
            it.leftMargin = dp(6); it.rightMargin = dp(6)
        })

        doneButton = pill("Done", accent = false) { onAccept() }
        controls.addView(doneButton, lp(WRAP, dp(56)))

        val back = repeatingPill("⌫") { byWord -> onBackspace(byWord) }
        controls.addView(back, lp(dp(52), dp(56)).also { it.leftMargin = dp(6) })

        addView(controls, lp(MATCH, WRAP))
    }


    fun setAmplitude(a: Float) = waveform.push(a)

    /**
     * Rebuilds the surface for [state].
     *
     * The status line carries only things the field cannot: a timer, progress,
     * an error. It is hidden the rest of the time -- the dictated text is
     * already in front of the user, in the field they are typing into, and
     * repeating it on the keyboard is clutter.
     */
    fun render(state: UiState) {
        when (state) {
            is UiState.NeedsSetup -> {
                // Only the mic changes. Space, newline, backspace and the
                // globe all work without a key, so taking them away would
                // strand someone mid-sentence in another app.
                setStatus(state.reason, FG_DIM)
                waveform.visibility = GONE
                micButton.text = "Set up Tyvo to use"
                micButton.background = roundedDrawable(ACCENT, dp(14).toFloat())
                micIsSetupPrompt = true
                undoButton.visibility = GONE
                doneButton.visibility = GONE
                customRow.visibility = GONE
                showIdleActions(canRetry = false)
            }

            is UiState.Idle -> {
                setStatus(state.lastError, WARN)
                waveform.visibility = GONE
                micIsSetupPrompt = false
                micButton.text = "Speak"
                micButton.background = roundedDrawable(ACCENT, dp(14).toFloat())
                undoButton.visibility = GONE
                doneButton.visibility = GONE
                customRow.visibility = GONE
                showIdleActions(state.canRetry)
            }

            is UiState.Recording -> {
                setStatus("Listening   ${fmt(state.elapsedMs)}", REC)
                waveform.visibility = VISIBLE
                micButton.text = "Done"
                micButton.background = roundedDrawable(REC, dp(14).toFloat())
                undoButton.visibility = GONE
                doneButton.visibility = GONE
                customRow.visibility = GONE
                actionStrip.removeAllViews()
            }

            is UiState.Working -> {
                setStatus("${state.step}…", FG_DIM)
                waveform.visibility = GONE
                micButton.text = "…"
                micButton.background = roundedDrawable(ACCENT_DIM, dp(14).toFloat())
                undoButton.visibility = GONE
                doneButton.visibility = GONE
                customRow.visibility = GONE
                actionStrip.removeAllViews()
            }

            is UiState.Review -> {
                // No preview of the text: it is already in the field.
                setStatus(
                    state.note?.let { if (state.busy) "$it…" else it },
                    if (state.busy) FG_DIM else WARN,
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

    /** Shows [text], or collapses the line entirely when there is nothing to say. */
    private fun setStatus(text: String?, color: Int) {
        if (text.isNullOrBlank()) {
            status.visibility = GONE
        } else {
            status.visibility = VISIBLE
            status.text = text
            status.setTextColor(color)
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
        actionStrip.addView(chip("Space") { onSpace() })
        actionStrip.addView(chip("Newline") { onNewline() })
        actionStrip.addView(chip("Settings") { onOpenSettings() })
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

    /**
     * A key that fires once on tap, then repeats while held, then starts
     * deleting whole words.
     *
     * The escalation matters: clearing a sentence one character at a time is
     * slow enough that people give up and go poke at the text field instead.
     * Timings follow the platform's own feel -- a pause before the repeat
     * starts so a tap is never mistaken for a hold, then acceleration.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun repeatingPill(label: String, onFire: (byWord: Boolean) -> Unit): TextView =
        TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(FG)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            background = roundedDrawable(SURFACE, dp(13).toFloat())
            isClickable = true

            var repeats = 0
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            lateinit var tick: Runnable

            tick = Runnable {
                repeats++
                // Characters first, then words once it is clearly a hold.
                val byWord = repeats >= REPEATS_BEFORE_WORDS
                onFire(byWord)
                val delay = if (byWord) WORD_REPEAT_MS else CHAR_REPEAT_MS
                handler.postDelayed(tick, delay)
            }

            setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        v.isPressed = true
                        repeats = 0
                        onFire(false)
                        handler.postDelayed(tick, FIRST_REPEAT_DELAY_MS)
                        true
                    }
                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL -> {
                        v.isPressed = false
                        handler.removeCallbacks(tick)
                        true
                    }
                    else -> false
                }
            }
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

        /** Long enough that a tap is never mistaken for a hold. */
        const val FIRST_REPEAT_DELAY_MS = 400L
        const val CHAR_REPEAT_MS = 55L
        /** Roughly a second of characters before escalating to words. */
        const val REPEATS_BEFORE_WORDS = 14
        const val WORD_REPEAT_MS = 130L
    }
}
