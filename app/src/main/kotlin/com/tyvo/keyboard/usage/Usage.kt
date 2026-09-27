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
package com.tyvo.keyboard.usage

/** What one API call cost, as reported by the provider. */
data class UsageEvent(
    val day: String,
    val provider: String,
    val model: String,
    val kind: Kind,
    val calls: Int = 1,
    /** Seconds of audio sent, transcription only. */
    val audioSeconds: Long = 0,
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
) {
    enum class Kind { TRANSCRIBE, POLISH }

    val totalTokens: Long get() = promptTokens + completionTokens
}

/**
 * One day's usage of one model, which is the grain the screen shows.
 *
 * Kept per model rather than per provider: the whole point is to see what a
 * choice costs, and switching model is the main lever the user has.
 */
data class DayUsage(
    val day: String,
    val provider: String,
    val model: String,
    val kind: UsageEvent.Kind,
    val calls: Long,
    val audioSeconds: Long,
    val promptTokens: Long,
    val completionTokens: Long,
) {
    val totalTokens: Long get() = promptTokens + completionTokens
}
