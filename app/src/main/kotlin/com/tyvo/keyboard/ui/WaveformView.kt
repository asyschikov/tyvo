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
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.max

/**
 * Scrolling amplitude bars.
 *
 * Purely feedback: it exists so the user can see the mic is live and roughly
 * how loud they are, which is the difference between trusting a dictation
 * button and tapping it twice.
 */
@SuppressLint("ViewConstructor")
class WaveformView(context: Context) : View(context) {

    private val levels = ArrayDeque<Float>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4C7DF0")
    }
    private val rect = RectF()
    private var capacity = 48

    fun push(level: Float) {
        levels.addLast(level.coerceIn(0f, 1f))
        while (levels.size > capacity) levels.removeFirst()
        invalidate()
    }

    fun reset() {
        levels.clear()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val barTotal = density() * 5f
        capacity = max(12, (w / barTotal).toInt())
        while (levels.size > capacity) levels.removeFirst()
    }

    override fun onDraw(canvas: Canvas) {
        if (levels.isEmpty()) return
        val d = density()
        val barW = 3f * d
        val gap = 2f * d
        val step = barW + gap
        val midY = height / 2f
        val maxH = height * 0.9f

        // Newest bar sits at the right edge and older ones scroll left.
        var x = width - levels.size * step
        levels.forEach { level ->
            val h = max(2f * d, level * maxH)
            rect.set(x, midY - h / 2f, x + barW, midY + h / 2f)
            paint.alpha = (90 + 165 * level).toInt().coerceIn(90, 255)
            canvas.drawRoundRect(rect, barW / 2f, barW / 2f, paint)
            x += step
        }
    }

    private fun density() = resources.displayMetrics.density
}
