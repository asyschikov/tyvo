package com.tyvo.keyboard.data

/**
 * Whether the app is actually configured to dictate, and what to tell the
 * user if not.
 *
 * Worth its own type because the failure modes are easy to hit -- picking
 * Anthropic for polish while holding only an Anthropic key leaves you with a
 * working polisher and no way to transcribe -- and a keyboard is the worst
 * possible place to discover that.
 */
data class Readiness(
    val canDictate: Boolean,
    val canPolish: Boolean,
    val problem: String?,
) {
    companion object {
        fun of(s: Settings): Readiness {
            val tp = s.transcribeProvider
            val pp = s.polishProvider
            val transcribeKey = s.keyFor(tp).isNotBlank()
            val polishKey = pp == PolishProvider.NONE || s.keyFor(pp).isNotBlank()

            val problem = when {
                !transcribeKey ->
                    "Add a ${tp.label} API key to dictate, or switch transcription provider."
                !polishKey ->
                    "Add a ${pp.label} API key to polish, or turn polish off."
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
