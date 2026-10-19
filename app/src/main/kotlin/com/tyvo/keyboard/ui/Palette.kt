package com.tyvo.keyboard.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color

/**
 * The colours the keyboard and the floating mic draw with, in one theme.
 *
 * Shared so the bubble reads as the same product as the keyboard it stands
 * in for, rather than a second app's chrome floating over the first.
 */
internal data class Palette(
    val bg: Int,
    val surface: Int,
    val fg: Int,
    val fgDim: Int,
    val fgFaint: Int,
    val accent: Int,
    val accentDim: Int,
    val rec: Int,
    val warn: Int,
    val onAccent: Int,
) {
    companion object {
        /** Picked from the device's night-mode setting at the time of the call. */
        fun of(context: Context): Palette =
            if (context.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            ) DARK else LIGHT

        // Two palettes, picked from the device's night-mode setting. A
        // keyboard sits against whatever app is showing, so a dark bar under
        // a light chat window is the one piece of chrome that cannot be
        // ignored.
        private val DARK = Palette(
            bg = Color.parseColor("#16181D"),
            surface = Color.parseColor("#262A32"),
            fg = Color.parseColor("#E8EAED"),
            fgDim = Color.parseColor("#A8AEBB"),
            fgFaint = Color.parseColor("#6B7280"),
            accent = Color.parseColor("#4C7DF0"),
            accentDim = Color.parseColor("#33507F"),
            rec = Color.parseColor("#E5484D"),
            warn = Color.parseColor("#F5A524"),
            onAccent = Color.WHITE,
        )

        private val LIGHT = Palette(
            bg = Color.parseColor("#ECEEF2"),
            surface = Color.parseColor("#FFFFFF"),
            fg = Color.parseColor("#1B1D22"),
            fgDim = Color.parseColor("#5A6070"),
            fgFaint = Color.parseColor("#9AA1B0"),
            accent = Color.parseColor("#3A63D0"),
            accentDim = Color.parseColor("#A9BCEC"),
            rec = Color.parseColor("#C9343A"),
            warn = Color.parseColor("#9A6200"),
            onAccent = Color.WHITE,
        )
    }
}
