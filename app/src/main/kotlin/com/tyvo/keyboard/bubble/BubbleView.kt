package com.tyvo.keyboard.bubble

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import androidx.core.content.ContextCompat
import com.tyvo.keyboard.R
import com.tyvo.keyboard.ime.UiState
import com.tyvo.keyboard.ui.Palette
import kotlin.math.hypot

/**
 * The floating mic: a round button that tells you what Tyvo is doing.
 *
 * Tap to start, tap to stop. Hold after a dictation to open the edit panel.
 * Drag to move it -- along the edge, or across to the other side.
 *
 * Wears the app icon -- gradient and all -- so it reads as Tyvo at a glance,
 * next to someone else's keyboard. Drawn on a canvas rather than built from
 * views: it is one circle and a mark, and the recording halo has to follow
 * the voice every frame.
 */
@SuppressLint("ViewConstructor")
class BubbleView(
    context: Context,
    /** The circle's diameter. */
    circleDp: Int,
    /** View alpha while resting: idle, or waiting to be edited. */
    private val restingAlpha: Float,
) : View(context) {

    private val c = Palette.of(context)

    var onTap: () -> Unit = {}
    var onLongPress: () -> Unit = {}
    /** Finger movement since the last call, in pixels. */
    var onDrag: (dx: Float, dy: Float) -> Unit = { _, _ -> }
    /** [rawX] is where the finger left the screen, for picking a side. */
    var onDragEnd: (rawX: Float) -> Unit = {}

    private var state: UiState = UiState.Idle()
    private var level = 0f
    private var spin = 0f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()

    /** The launcher icon's mark: waveform above the word, white. */
    private val mark = ContextCompat.getDrawable(context, R.drawable.ic_launcher_fg)!!.mutate()
    private var gradient: LinearGradient? = null

    /** Edge to edge, halo room included. */
    val sizePx: Int = dp(circleDp + 2 * (HALO_DP + 4))
    private val radius = dp(circleDp) / 2f

    init {
        // Shadow from the circle, not the square the halo needs.
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val cx = view.width / 2
                val cy = view.height / 2
                val r = radius.toInt()
                outline.setOval(cx - r, cy - r, cx + r, cy + r)
            }
        }
        elevation = dp(6).toFloat()
        render(state)
    }

    fun render(state: UiState) {
        this.state = state
        // A phone that dims and locks mid-sentence cuts the dictation short.
        keepScreenOn = state is UiState.Recording
        alpha = if (state is UiState.Idle || state is UiState.Review) restingAlpha else 1f
        contentDescription = when (state) {
            is UiState.NeedsSetup -> "Set up Tyvo"
            is UiState.Recording -> "Stop dictating"
            is UiState.Working -> "Tyvo is ${state.step.lowercase()}"
            is UiState.Review -> "Dictate. Hold to edit what Tyvo wrote."
            is UiState.Idle -> "Dictate with Tyvo"
        }
        if (state !is UiState.Recording) level = 0f
        invalidate()
    }

    /** Latest microphone level, 0..1. Smoothed so the halo breathes rather than flickers. */
    fun setAmplitude(a: Float) {
        level += (a.coerceIn(0f, 1f) - level) * 0.5f
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(sizePx, sizePx)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f

        val body = when (state) {
            is UiState.NeedsSetup -> c.surface
            is UiState.Recording -> c.rec
            is UiState.Working -> c.accent
            else -> c.accent
        }

        if (state is UiState.Recording) {
            fill.color = c.rec
            fill.alpha = 0x55
            canvas.drawCircle(cx, cy, radius + dp(2) + level * dp(HALO_DP), fill)
        }

        // Ready states wear the icon's own gradient; the rest are a flat
        // colour, so a glance tells recording and working apart from idle.
        val iconLook = state is UiState.Idle || state is UiState.Review
        fill.color = body
        fill.shader = if (iconLook) iconGradient(cx, cy) else null
        fill.alpha = 0xFF
        canvas.drawCircle(cx, cy, radius, fill)
        fill.shader = null

        val glyph = if (state is UiState.NeedsSetup) c.fgDim else c.onAccent
        drawMark(canvas, cx, cy, glyph)
        if (state is UiState.Working) drawSpinner(canvas, cx, cy, glyph)

        // A dot marks text that can still be edited: the only hint that a
        // long press does something.
        val dot = when {
            state is UiState.Review -> c.onAccent
            (state as? UiState.Idle)?.lastError != null -> c.warn
            else -> null
        }
        if (dot != null) {
            val dx = cx + radius * 0.68f
            val dy = cy - radius * 0.68f
            fill.color = body
            fill.alpha = 0xFF
            canvas.drawCircle(dx, dy, dp(6).toFloat(), fill)
            fill.color = dot
            canvas.drawCircle(dx, dy, dp(4).toFloat(), fill)
        }
    }

    /**
     * The adaptive icon's foreground is a 108-unit canvas whose visible
     * circle has radius 36, so drawing it at 3x our radius lines its safe
     * zone up with our circle -- the same framing the launcher gives it.
     */
    private fun drawMark(canvas: Canvas, cx: Float, cy: Float, color: Int) {
        val half = (radius * 1.5f).toInt()
        mark.setBounds(cx.toInt() - half, cy.toInt() - half, cx.toInt() + half, cy.toInt() + half)
        mark.setTint(color)
        mark.draw(canvas)
    }

    /** The launcher background's diagonal gradient, fitted to our circle. */
    private fun iconGradient(cx: Float, cy: Float): LinearGradient =
        gradient ?: LinearGradient(
            cx - radius, cy - radius, cx + radius, cy + radius,
            Color.parseColor("#6E8EF7"), Color.parseColor("#3350D4"),
            Shader.TileMode.CLAMP,
        ).also { gradient = it }

    private fun drawSpinner(canvas: Canvas, cx: Float, cy: Float, color: Int) {
        // Around the rim, so the mark stays readable inside it.
        val r = radius - dp(4)
        stroke.color = color
        stroke.strokeWidth = dp(2).toFloat()
        stroke.strokeCap = Paint.Cap.ROUND
        rect.set(cx - r, cy - r, cx + r, cy + r)
        canvas.drawArc(rect, spin, 90f, false, stroke)
        spin = (spin + 9f) % 360f
        postInvalidateOnAnimation()
    }

    // ---- touch -----------------------------------------------------------

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false
    private var longFired = false

    private val longPress = Runnable {
        longFired = true
        onLongPress()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX; downY = e.rawY
                lastX = e.rawX; lastY = e.rawY
                dragging = false
                longFired = false
                isPressed = true
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && !longFired &&
                    hypot(e.rawX - downX, e.rawY - downY) > slop
                ) {
                    dragging = true
                    removeCallbacks(longPress)
                }
                if (dragging) {
                    onDrag(e.rawX - lastX, e.rawY - lastY)
                    lastX = e.rawX; lastY = e.rawY
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                isPressed = false
                when {
                    dragging -> onDragEnd(e.rawX)
                    !longFired -> performClick()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                isPressed = false
                if (dragging) onDragEnd(e.rawX)
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        onTap()
        return true
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private companion object {
        /** How far the recording halo reaches at full volume. */
        const val HALO_DP = 8
    }
}
