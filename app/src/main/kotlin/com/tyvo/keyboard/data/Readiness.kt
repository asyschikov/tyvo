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
package com.tyvo.keyboard.data

/**
 * Whether the app is actually configured to dictate, and what to tell the
 * user if not.
 *
 * Worth its own type because a keyboard is the worst possible place to
 * discover that a key is missing -- the message belongs in settings, before
 * you are mid-sentence in someone else's chat window.
 */
data class Readiness(
    val canDictate: Boolean,
    val problem: String?,
) {
    companion object {
        private fun article(word: String): String =
            if (word.firstOrNull()?.uppercaseChar() in setOf('A', 'E', 'I', 'O', 'U')) "an" else "a"

        fun of(s: Settings): Readiness {
            val p = s.provider
            val hasKey = s.key(p).isNotBlank()
            return Readiness(
                canDictate = hasKey,
                problem = if (hasKey) null
                else "Add ${article(p.label)} ${p.label} API key to start dictating.",
            )
        }
    }
}
