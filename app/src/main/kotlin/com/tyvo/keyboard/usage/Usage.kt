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

/**
 * Every day's rows folded into one per model and stage, for the all-time
 * view. [DayUsage.day] is left blank: the rows span every recorded day.
 */
fun List<DayUsage>.perModel(): List<DayUsage> =
    groupBy { Triple(it.provider, it.model, it.kind) }
        .map { (k, rs) ->
            DayUsage(
                day = "",
                provider = k.first,
                model = k.second,
                kind = k.third,
                calls = rs.sumOf { it.calls },
                audioSeconds = rs.sumOf { it.audioSeconds },
                promptTokens = rs.sumOf { it.promptTokens },
                completionTokens = rs.sumOf { it.completionTokens },
            )
        }
        .sortedWith(compareBy<DayUsage> { it.kind }.thenBy { it.model })
