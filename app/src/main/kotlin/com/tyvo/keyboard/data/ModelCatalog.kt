package com.tyvo.keyboard.data

/**
 * A model the user can pick from a dropdown, rather than having to type an
 * exact ID from memory.
 *
 * [note] is shown under the name. It exists because the meaningful difference
 * between these is rarely the name -- it is cost, latency, and whether the
 * account can reach the model at all.
 */
data class ModelOption(
    val id: String,
    val label: String,
    val note: String,
)

/**
 * Curated model lists per provider and stage.
 *
 * Deliberately short. A full catalogue would be noise: for a keyboard, the
 * choice is "fast and cheap" versus "better at hard sentences", and anything
 * else belongs in the free-text override.
 *
 * Not every account can reach every model. Mistral in particular gates larger
 * models by subscription tier and reports it as HTTP 429 "Rate limit
 * exceeded" -- which reads like throttling but is really "not included in
 * your plan". The Test button distinguishes the two.
 */
object ModelCatalog {

    val OPENAI_TRANSCRIBE = listOf(
        ModelOption(
            "gpt-4o-mini-transcribe",
            "GPT-4o mini transcribe",
            "Fast and cheap. Good default for dictation.",
        ),
        ModelOption(
            "gpt-4o-transcribe",
            "GPT-4o transcribe",
            "More accurate on accents and noise; costs more.",
        ),
        ModelOption(
            "whisper-1",
            "Whisper v1",
            "Legacy. Cheapest, and the most widely available.",
        ),
    )

    val MISTRAL_TRANSCRIBE = listOf(
        ModelOption(
            "voxtral-mini-latest",
            "Voxtral mini",
            "The only Voxtral tier that transcribes. Recommended.",
        ),
        ModelOption(
            "voxtral-mini-2602",
            "Voxtral mini (pinned)",
            "Same model, pinned so it cannot change under you.",
        ),
    )

    val OPENAI_POLISH = listOf(
        ModelOption(
            "gpt-4o-mini",
            "GPT-4o mini",
            "Fast and cheap. Handles clean-up well.",
        ),
        ModelOption(
            "gpt-4.1-mini",
            "GPT-4.1 mini",
            "Better instruction-following on tricky corrections.",
        ),
        ModelOption(
            "gpt-4o",
            "GPT-4o",
            "Best quality, noticeably slower for a keyboard.",
        ),
    )

    val MISTRAL_POLISH = listOf(
        ModelOption(
            "ministral-3b-latest",
            "Ministral 3B",
            "Fastest. Available on every tier.",
        ),
        ModelOption(
            "ministral-8b-latest",
            "Ministral 8B",
            "Better on long or messy dictation. Still quick.",
        ),
        ModelOption(
            "ministral-14b-latest",
            "Ministral 14B",
            "Strongest model available on every tier.",
        ),
        ModelOption(
            "open-mistral-nemo",
            "Mistral Nemo",
            "Open-weights mid-size. Good multilingual clean-up.",
        ),
        ModelOption(
            "mistral-small-latest",
            "Mistral Small",
            "Stronger, but gated on lower subscription tiers.",
        ),
        ModelOption(
            "mistral-medium-latest",
            "Mistral Medium",
            "Best clean-up quality; paid tiers only.",
        ),
    )

    fun transcribeFor(p: Provider): List<ModelOption> = when (p) {
        Provider.OPENAI -> OPENAI_TRANSCRIBE
        Provider.MISTRAL -> MISTRAL_TRANSCRIBE
    }

    fun polishFor(p: Provider): List<ModelOption> = when (p) {
        Provider.OPENAI -> OPENAI_POLISH
        Provider.MISTRAL -> MISTRAL_POLISH
    }
}
