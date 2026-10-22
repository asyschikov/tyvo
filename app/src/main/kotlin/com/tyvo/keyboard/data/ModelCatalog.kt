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
            "voxtral-small-transcribe-3",
            "Voxtral Small Transcribe 3",
            "Most accurate, $0.24/hour. Recommended.",
        ),
        ModelOption(
            "voxtral-mini-transcribe-3",
            "Voxtral Mini Transcribe 3",
            "A little faster and cheaper, $0.21/hour.",
        ),
        ModelOption(
            "voxtral-mini-2602",
            "Voxtral Mini Transcribe 2",
            "Previous generation, $0.18/hour.",
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
            "mistral-small-2603",
            "Mistral Small 4",
            "Latest Mistral Small. A balance of price and accuracy.",
        ),
        ModelOption(
            "ministral-8b-latest",
            "Ministral 8B",
            "Cheaper and faster, a little less accurate.",
        ),
        ModelOption(
            "ministral-3b-latest",
            "Ministral 3B",
            "Cheapest and fastest. Misses corrections when several are on.",
        ),
        ModelOption(
            "ministral-14b-latest",
            "Ministral 14B",
            "Between Ministral 8B and Mistral Small.",
        ),
        ModelOption(
            "open-mistral-nemo",
            "Mistral Nemo",
            "Older open-weights model. Currently answers as Ministral 8B.",
        ),
        ModelOption(
            "mistral-medium-latest",
            "Mistral Medium",
            "Larger and pricier than Small. May need a paid tier.",
        ),
        ModelOption(
            "mistral-large-4",
            "Mistral Large 4",
            "Largest and most expensive, and the slowest to answer.",
        ),
    )

    val XAI_TRANSCRIBE = listOf(
        ModelOption(
            "grok-voice-transcribe-2.0",
            "Grok Voice Transcribe",
            "23 languages, Russian and English among them.",
        ),
    )

    // Latency is the deciding factor here: Grok's reasoning models all answer
    // correctly but take 2-11s, and a keyboard cannot wait that long. The
    // non-reasoning model handles spoken corrections just as well at 0.6s.
    val XAI_POLISH = listOf(
        ModelOption(
            "grok-4.20-0309-non-reasoning",
            "Grok 4.20 (non-reasoning)",
            "Fastest by far, and handles corrections. Recommended.",
        ),
        ModelOption(
            "grok-4.7",
            "Grok 4.7",
            "xAI's flagship. Same results, around 2s slower.",
        ),
    )

    // Gemini 2.5 is closed to new API keys: it still appears in the models
    // list, but generateContent answers 404 "no longer available to new
    // users". Everything here is 3.x, which is what a key made today can call.
    val GEMINI_TRANSCRIBE = listOf(
        ModelOption(
            "gemini-3.5-flash-lite",
            "Gemini 3.5 Flash Lite",
            "Cheapest option. Audio is billed as tokens.",
        ),
        ModelOption(
            "gemini-3.5-flash",
            "Gemini 3.5 Flash",
            "More accurate on noisy or accented speech.",
        ),
        ModelOption(
            "gemini-flash-lite-latest",
            "Gemini Flash Lite (latest)",
            "Tracks whichever Flash Lite is current.",
        ),
    )

    val GEMINI_POLISH = listOf(
        ModelOption("gemini-3.5-flash-lite", "Gemini 3.5 Flash Lite", "Fastest and cheapest."),
        ModelOption("gemini-3.5-flash", "Gemini 3.5 Flash", "Better at awkward corrections."),
        ModelOption(
            "gemini-flash-lite-latest",
            "Gemini Flash Lite (latest)",
            "Tracks whichever Flash Lite is current.",
        ),
    )

    fun transcribeFor(p: Provider): List<ModelOption> = when (p) {
        Provider.OPENAI -> OPENAI_TRANSCRIBE
        Provider.MISTRAL -> MISTRAL_TRANSCRIBE
        Provider.XAI -> XAI_TRANSCRIBE
        Provider.GEMINI -> GEMINI_TRANSCRIBE
    }

    fun polishFor(p: Provider): List<ModelOption> = when (p) {
        Provider.OPENAI -> OPENAI_POLISH
        Provider.MISTRAL -> MISTRAL_POLISH
        Provider.XAI -> XAI_POLISH
        Provider.GEMINI -> GEMINI_POLISH
    }
}
