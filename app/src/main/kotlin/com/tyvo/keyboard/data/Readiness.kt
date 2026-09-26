package com.tyvo.keyboard.data

/**
 * Whether the app is actually configured to dictate, and what to tell the
 * user if not.
 *
 * Worth its own type because the failure modes are easy to hit -- holding a
 * key for one provider while both stages point at the other leaves you with
 * no way to dictate -- and a keyboard is the worst possible place to discover
 * that.
 */
data class Readiness(
    val canDictate: Boolean,
    val canPolish: Boolean,
    val problem: String?,
) {
    companion object {
        private fun article(word: String): String =
            if (word.firstOrNull()?.uppercaseChar() in setOf('A', 'E', 'I', 'O', 'U')) "an" else "a"

        fun of(s: Settings): Readiness {
            val tp = s.transcribeProvider
            val pp = s.polishProvider
            val transcribeKey = s.keyFor(tp).isNotBlank()
            val polishKey = pp == PolishProvider.NONE || s.keyFor(pp).isNotBlank()

            val problem = when {
                !transcribeKey ->
                    "Add ${article(tp.label)} ${tp.label} API key to dictate, " +
                        "or switch transcription provider."
                !polishKey ->
                    "Add ${article(pp.label)} ${pp.label} API key to polish, " +
                        "or turn polish off."
                else -> null
            }
            return Readiness(
                canDictate = transcribeKey,
                canPolish = polishKey && pp != PolishProvider.NONE,
                problem = problem,
            )
        }
    }
}
