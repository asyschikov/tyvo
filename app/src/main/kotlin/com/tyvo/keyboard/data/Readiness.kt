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
