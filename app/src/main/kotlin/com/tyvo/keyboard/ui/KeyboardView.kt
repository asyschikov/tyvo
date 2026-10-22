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
/**
 * The keyboard's background in each theme, public so the IME can tint the
 * system's own key row to match.
 */
object KeyboardColors {
    val BG_DARK: Int = Color.parseColor("#16181D")
    val BG_LIGHT: Int = Color.parseColor("#ECEEF2")
}

/** Fallback pads shown instead of the voice UI when voice does not fit the field. */
enum class PadKind {
    /** Digits and the separators number, phone and date/time fields use. */
    NUMERIC,

    /** A compact QWERTY with a digits/symbols layer. */
    TEXT,
}

@SuppressLint("ViewConstructor")
class KeyboardView(context: Context) : LinearLayout(context) {

    /** Resolved once per view: the IME is recreated when night mode flips. */
    private val c: Palette = Palette.of(context)

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

    /**
     * Backspace held while dictated text is still staged. Returns true if it
     * deleted that text; false falls back to the ordinary held delete.
     */
    var onBackspaceHold: () -> Boolean = { false }

    /** Staged text exists, so a held backspace means "delete the dictation". */
    private var holdDeletesStaged = false
    var onNewline: () -> Unit = {}
    var onSpace: () -> Unit = {}
    var onEnableBubble: () -> Unit = {}

    /** A character key on a fallback pad. */
    var onPadKey: (String) -> Unit = {}

    /** Enter on a fallback pad: the field's own action, not a raw newline. */
    var onEnter: () -> Unit = {}

    /** Cancel in compact mode: back to the keyboard that launched us. */
    var onCancel: () -> Unit = {}

    /**
     * Offer the floating mic in the idle strip. Set by the IME before each
     * render, since the switch for it lives in system settings.
     */
    var offerBubble: Boolean = false

    private val status: TextView
    private val waveform: WaveformView
    private val actionStrip: LinearLayout
    private val actionScroll: HorizontalScrollView
    private val micButton: TextView
    private val undoButton: TextView
    private val doneButton: TextView
    private val controls: LinearLayout
    private val compactRow: LinearLayout
    private val compactMic: TextView
    private val cancelButton: TextView
    private val padView: LinearLayout

    /** True while the mic button is standing in as the setup prompt. */
    private var micIsSetupPrompt = false

    /**
     * Fallback pad shown instead of the voice UI, or null for the voice UI.
     * Set by the IME from the field's context.
     */
    var pad: PadKind? = null
        set(value) {
            val changed = field != value
            field = value
            if (value != null) {
                if (changed) {
                    shiftOn = false
                    symbolsOn = false
                }
                buildPad(value)
            }
            applyChrome()
        }

    /**
     * Compact dictate-and-return UI, for when another keyboard launched us
     * from its mic key. The IME keeps this and [pad] mutually exclusive.
     */
    var compact = false
        set(value) {
            field = value
            applyChrome()
        }

    /** One-shot shift on the text pad: the next letter only. */
    private var shiftOn = false

    /** Digits/symbols layer of the text pad, off the letters. */
    private var symbolsOn = false

    init {
        orientation = VERTICAL
        setBackgroundColor(c.bg)
        val pad = dp(8)
        // One gap value for the space above the first row and between rows,
        // so the keyboard does not sit tighter against its own top edge than
        // its rows sit against each other.
        val rowGap = dp(8)
        setPadding(pad, rowGap, pad, dp(10))

        // Keep the control row clear of the gesture-nav pill.
        setOnApplyWindowInsetsListener { v, insets ->
            val bars = WindowInsetsCompat.toWindowInsetsCompat(insets)
                .getInsets(WindowInsetsCompat.Type.navigationBars())
            v.setPadding(pad, rowGap, pad, dp(10) + bars.bottom)
            insets
        }

        // --- status line -------------------------------------------------
        status = TextView(context).apply {
            setTextColor(c.fgDim)
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

        // --- action strip -------------------------------------------------
        actionStrip = LinearLayout(context).apply { orientation = HORIZONTAL }
        actionScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(actionStrip)
        }
        // Exactly the chip height, not 46: the extra 8dp was slack inside the
        // strip that landed below the chips, so the gap to the control row
        // read as double the gap above the strip.
        addView(actionScroll, lp(MATCH, dp(38)).also { it.bottomMargin = rowGap })

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
            setTextColor(c.onAccent)
            setTypeface(typeface, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            background = roundedDrawable(c.accent, dp(14).toFloat())
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

        // --- compact mode (voice subtype) ------------------------------------
        // Dictate and return: one mic, one way out. No action strip, no
        // review controls -- the text lands and the keyboard switches back.
        compactRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = GONE
        }
        cancelButton = pill("Cancel", accent = false) { onCancel() }
        compactRow.addView(cancelButton, lp(WRAP, dp(56)))
        compactMic = TextView(context).apply {
            text = "Hold to talk"
            gravity = Gravity.CENTER
            setTextColor(c.onAccent)
            setTypeface(typeface, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            background = roundedDrawable(c.accent, dp(14).toFloat())
            isClickable = true
            setOnClickListener {
                if (micIsSetupPrompt) onOpenSettings() else onMicPressed()
            }
        }
        compactRow.addView(compactMic, LayoutParams(0, dp(56), 1f).also {
            it.leftMargin = dp(6)
        })
        addView(compactRow, lp(MATCH, WRAP))

        // --- fallback pads ----------------------------------------------------
        // Built per pad kind when the IME sets [pad]; see buildPad.
        padView = LinearLayout(context).apply {
            orientation = VERTICAL
            visibility = GONE
        }
        addView(padView, lp(MATCH, WRAP))
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
        // Holds the screen awake while the keyboard is showing: a phone that
        // dims and locks mid-sentence cuts the dictation short.
        keepScreenOn = state is UiState.Recording
        holdDeletesStaged = state is UiState.Review
        // The compact UI and the fallback pad replace the voice surface
        // entirely; only the status line (and the waveform while recording)
        // still react to state.
        if (compact && pad == null) {
            renderCompact(state)
            return
        }
        if (pad != null) {
            renderPadStatus(state)
            return
        }
        when (state) {
            is UiState.NeedsSetup -> {
                // Only the mic changes. Space, newline, backspace and the
                // globe all work without a key, so taking them away would
                // strand someone mid-sentence in another app.
                setStatus(state.reason, c.fgDim)
                waveform.visibility = GONE
                micButton.text = "Set up Tyvo to use"
                micButton.background = roundedDrawable(c.accent, dp(14).toFloat())
                micIsSetupPrompt = true
                undoButton.visibility = GONE
                doneButton.visibility = GONE
                showIdleActions(canRetry = false)
            }

            is UiState.Idle -> {
                setStatus(state.lastError, c.warn)
                waveform.visibility = GONE
                micIsSetupPrompt = false
                micButton.text = "Speak"
                micButton.background = roundedDrawable(c.accent, dp(14).toFloat())
                undoButton.visibility = GONE
                doneButton.visibility = GONE
                showIdleActions(state.canRetry)
            }

            is UiState.Recording -> {
                setStatus("Listening   ${fmt(state.elapsedMs)}", c.rec)
                waveform.visibility = VISIBLE
                micButton.text = "Done"
                micButton.background = roundedDrawable(c.rec, dp(14).toFloat())
                undoButton.visibility = GONE
                doneButton.visibility = GONE
                actionStrip.removeAllViews()
            }

            is UiState.Working -> {
                setStatus("${state.step}…", c.fgDim)
                waveform.visibility = GONE
                micButton.text = "…"
                micButton.background = roundedDrawable(c.accentDim, dp(14).toFloat())
                undoButton.visibility = GONE
                doneButton.visibility = GONE
                actionStrip.removeAllViews()
            }

            is UiState.Review -> {
                // No preview of the text: it is already in the field.
                setStatus(
                    state.note?.let { if (state.busy) "$it…" else it },
                    if (state.busy) c.fgDim else c.warn,
                )
                waveform.visibility = GONE
                micButton.text = "Speak"
                micButton.background = roundedDrawable(c.accent, dp(14).toFloat())
                undoButton.visibility = if (state.canUndo) VISIBLE else GONE
                doneButton.visibility = VISIBLE
                showReviewActions(state, enabled = !state.busy)
            }
        }
    }

    /**
     * Compact mode: mic and cancel, nothing else. The service switches back
     * to the keyboard that launched us as soon as text lands or the user
     * cancels.
     */
    private fun renderCompact(state: UiState) {
        when (state) {
            is UiState.NeedsSetup -> {
                setStatus(state.reason, c.fgDim)
                waveform.visibility = GONE
                compactMic.text = "Set up Tyvo to use"
                compactMic.background = roundedDrawable(c.accent, dp(14).toFloat())
                micIsSetupPrompt = true
            }

            is UiState.Idle -> {
                setStatus(state.lastError, c.warn)
                waveform.visibility = GONE
                compactMic.text = "Hold to talk"
                compactMic.background = roundedDrawable(c.accent, dp(14).toFloat())
                micIsSetupPrompt = false
            }

            is UiState.Recording -> {
                setStatus("Listening   ${fmt(state.elapsedMs)}", c.rec)
                waveform.visibility = VISIBLE
                compactMic.text = "Done"
                compactMic.background = roundedDrawable(c.rec, dp(14).toFloat())
            }

            is UiState.Working -> {
                setStatus("${state.step}…", c.fgDim)
                waveform.visibility = GONE
                compactMic.text = "…"
                compactMic.background = roundedDrawable(c.accentDim, dp(14).toFloat())
            }

            is UiState.Review -> {
                // Visible only between the text landing and the switch back
                // to the previous keyboard.
                setStatus(state.note, if (state.busy) c.fgDim else c.warn)
                waveform.visibility = GONE
                compactMic.text = "Hold to talk"
                compactMic.background = roundedDrawable(c.accent, dp(14).toFloat())
                micIsSetupPrompt = false
            }
        }
    }

    /**
     * Pad mode: only the status line (and the waveform while recording)
     * react to state. The pad works without an API key, so an unconfigured
     * keyboard still types here -- the hint says why there is no mic.
     */
    private fun renderPadStatus(state: UiState) {
        when (state) {
            is UiState.Recording -> {
                setStatus("Listening   ${fmt(state.elapsedMs)}", c.rec)
                waveform.visibility = VISIBLE
            }

            is UiState.Working -> {
                setStatus("${state.step}…", c.fgDim)
                waveform.visibility = GONE
            }

            is UiState.Review -> {
                setStatus(state.note, if (state.busy) c.fgDim else c.warn)
                waveform.visibility = GONE
            }

            is UiState.NeedsSetup -> {
                setStatus(state.reason, c.fgDim)
                waveform.visibility = GONE
            }

            is UiState.Idle -> {
                if (state.lastError != null) setStatus(state.lastError, c.warn)
                else setStatus(padHint(), c.fgDim)
                waveform.visibility = GONE
            }
        }
    }

    private fun padHint(): String = when (pad) {
        PadKind.NUMERIC -> "Number pad · voice is off in number fields"
        PadKind.TEXT -> "Keys · voice is off in this field"
        null -> ""
    }

    /** Shows exactly one of the voice UI, the compact UI, or the fallback pad. */
    private fun applyChrome() {
        val padOn = pad != null
        val compactOn = compact && !padOn
        padView.visibility = if (padOn) VISIBLE else GONE
        compactRow.visibility = if (compactOn) VISIBLE else GONE
        actionScroll.visibility = if (padOn || compactOn) GONE else VISIBLE
        controls.visibility = if (padOn || compactOn) GONE else VISIBLE
        if (padOn) waveform.visibility = GONE
    }

    // ---- fallback pads ---------------------------------------------------

    private fun buildPad(kind: PadKind) {
        padView.removeAllViews()
        when (kind) {
            PadKind.NUMERIC -> buildNumericPad()
            PadKind.TEXT -> buildTextPad()
        }
    }

    private fun rebuildPad() {
        pad?.let { buildPad(it) }
    }

    /**
     * Digits plus the separators number, phone and date/time fields actually
     * use. The rarer symbols (*, #, :, /) are one globe key away, on the
     * user's own keyboard.
     */
    private fun buildNumericPad() {
        listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf(".", ",", "-", "+"),
        ).forEach { labels ->
            padView.addView(keyRow().apply {
                labels.forEach { key(it) }
            }, rowLp())
        }
        padView.addView(padControlRow(), rowLp())
    }

    /**
     * A compact QWERTY for search, password and URI fields: one-shot shift,
     * and a ?123 layer for digits and the symbols passwords tend to demand.
     */
    private fun buildTextPad() {
        if (symbolsOn) {
            padView.addView(keyRow().apply {
                "1234567890".forEach { key(it.toString()) }
            }, rowLp())
            padView.addView(keyRow().apply {
                listOf("@", "#", "$", "%", "&", "*", "-", "+", "(", ")")
                    .forEach { key(it) }
            }, rowLp())
            padView.addView(keyRow().apply {
                listOf("!", "?", "'", "\"", ":", ";", "/", "_")
                    .forEach { key(it) }
            }, rowLp())
        } else {
            val letters =
                if (shiftOn) "QWERTYUIOPASDFGHJKLZXCVBNM" else "qwertyuiopasdfghjklzxcvbnm"
            padView.addView(keyRow().apply {
                letters.take(10).forEach { key(it.toString()) { typeLetter(it) } }
            }, rowLp())
            padView.addView(keyRow().apply {
                letters.substring(10, 19).forEach { key(it.toString()) { typeLetter(it) } }
            }, rowLp())
            padView.addView(keyRow().apply {
                key(if (shiftOn) "⇪" else "⇧", weight = 1.3f) {
                    shiftOn = !shiftOn
                    rebuildPad()
                }
                letters.substring(19).forEach { key(it.toString()) { typeLetter(it) } }
            }, rowLp())
        }
        // Bottom row: the layer toggle, space, and the two punctuation keys
        // an address or a sentence usually needs.
        padView.addView(keyRow().apply {
            key(if (symbolsOn) "ABC" else "?123", weight = 1.4f) {
                symbolsOn = !symbolsOn
                rebuildPad()
            }
            key("space", weight = 3.5f) { onSpace() }
            key(",")
            key(".")
        }, rowLp())
        padView.addView(padControlRow(), rowLp())
    }

    /** Emits a letter, then drops one-shot shift so the next one is lower-case. */
    private fun typeLetter(displayed: Char) {
        onPadKey(displayed.toString())
        if (shiftOn) {
            shiftOn = false
            rebuildPad()
        }
    }

    /** Globe, repeating backspace, enter -- shared by both pads. */
    private fun padControlRow(): LinearLayout =
        keyRow().apply {
            addView(pill("⌨", accent = false) { onSwitchKeyboard() }, lp(dp(52), dp(KEY_H)))
            addView(
                repeatingPill("⌫") { byWord -> onBackspace(byWord) },
                LayoutParams(0, dp(KEY_H), 1f).also {
                    it.leftMargin = dp(6)
                    it.rightMargin = dp(6)
                }
            )
            addView(pill("⏎", accent = true) { onEnter() }, lp(dp(52), dp(KEY_H)).also {
                it.leftMargin = dp(6)
            })
        }

    private fun keyRow(): LinearLayout =
        LinearLayout(context).apply { orientation = HORIZONTAL }

    /** Adds a character key to this row; defaults to emitting [label]. */
    private fun LinearLayout.key(
        label: String,
        weight: Float = 1f,
        onTap: () -> Unit = { onPadKey(label) },
    ) {
        addView(padKey(label) { onTap() }, LayoutParams(0, dp(KEY_H), weight).also {
            it.leftMargin = dp(3)
            it.rightMargin = dp(3)
        })
    }

    private fun padKey(label: String, onTap: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(c.fg)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            background = roundedDrawable(c.surface, dp(10).toFloat())
            isClickable = true
            setOnClickListener { onTap() }
        }

    private fun rowLp() = lp(MATCH, WRAP).also { it.bottomMargin = dp(6) }

    /** Shows [text], or collapses the line entirely when there is nothing to say. */
    private fun setStatus(text: String?, color: Int) {        if (text.isNullOrBlank()) {
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
        if (offerBubble) {
            actionStrip.addView(chip("Enable bubble") { onEnableBubble() })
        }
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
            background = roundedDrawable(if (selected) c.accent else c.surface, dp(11).toFloat())
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
            setTextColor(if (accent) c.onAccent else c.fg)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(14), 0, dp(14), 0)
            background = roundedDrawable(if (accent) c.accent else c.surface, dp(13).toFloat())
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
     *
     * While dictated text is staged, a hold deletes all of it instead. The
     * first character then waits for the finger to lift: deleting it on
     * touch-down would end the session before we knew it was a hold.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun repeatingPill(label: String, onFire: (byWord: Boolean) -> Unit): TextView =
        TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(c.fg)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            background = roundedDrawable(c.surface, dp(13).toFloat())
            isClickable = true

            var repeats = 0
            /** Touch-down did not fire, so lifting the finger must. */
            var firePending = false
            /** The hold deleted the staged text; nothing more this press. */
            var consumed = false
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            lateinit var tick: Runnable

            tick = Runnable {
                if (firePending) {
                    firePending = false
                    if (onBackspaceHold()) {
                        consumed = true
                        return@Runnable
                    }
                    onFire(false)
                    handler.postDelayed(tick, CHAR_REPEAT_MS)
                    return@Runnable
                }
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
                        consumed = false
                        firePending = holdDeletesStaged
                        if (!firePending) onFire(false)
                        handler.postDelayed(tick, FIRST_REPEAT_DELAY_MS)
                        true
                    }
                    android.view.MotionEvent.ACTION_UP -> {
                        v.isPressed = false
                        handler.removeCallbacks(tick)
                        // A tap on staged text: the one character it held back.
                        if (firePending && !consumed) onFire(false)
                        firePending = false
                        true
                    }
                    android.view.MotionEvent.ACTION_CANCEL -> {
                        v.isPressed = false
                        handler.removeCallbacks(tick)
                        firePending = false
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

        /** Height of one fallback-pad key row. */
        const val KEY_H = 48

        const val FIRST_REPEAT_DELAY_MS = 400L
        const val CHAR_REPEAT_MS = 55L
        /** Roughly a second of characters before escalating to words. */
        const val REPEATS_BEFORE_WORDS = 14
        const val WORD_REPEAT_MS = 130L
    }
}
