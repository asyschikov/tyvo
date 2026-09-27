package com.tyvo.keyboard.usage

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Records what each API call cost, so the user can see their own usage.
 *
 * Numbers come from the providers' own `usage` blocks rather than being
 * estimated locally: a token count we guessed would be worse than none, since
 * the whole point is to let someone sanity-check their bill.
 *
 * Rows are aggregated per day, model and kind on write, so the file stays
 * small no matter how much someone dictates -- a per-call log would grow
 * without bound for a screen that only ever shows totals.
 */
class UsageStore(baseDir: File) {

    constructor(context: Context) : this(context.filesDir)

    private val file = File(baseDir, "usage.json")
    private val lock = Any()

    /** Adds one call's usage to its day's running totals. */
    fun record(event: UsageEvent) = synchronized(lock) {
        val all = read().toMutableMap()
        val key = keyOf(event.day, event.provider, event.model, event.kind)
        val existing = all[key]
        all[key] = if (existing == null) {
            DayUsage(
                day = event.day,
                provider = event.provider,
                model = event.model,
                kind = event.kind,
                calls = event.calls.toLong(),
                audioSeconds = event.audioSeconds,
                promptTokens = event.promptTokens,
                completionTokens = event.completionTokens,
            )
        } else {
            existing.copy(
                calls = existing.calls + event.calls,
                audioSeconds = existing.audioSeconds + event.audioSeconds,
                promptTokens = existing.promptTokens + event.promptTokens,
                completionTokens = existing.completionTokens + event.completionTokens,
            )
        }
        write(all)
    }

    /** Every recorded day, newest first. */
    fun all(): List<DayUsage> =
        read().values.sortedWith(
            compareByDescending<DayUsage> { it.day }.thenBy { it.kind }.thenBy { it.model }
        )

    /** Rows for the last [days] days, newest first. */
    fun recent(days: Int): List<DayUsage> {
        val cutoff = dayKey(System.currentTimeMillis() - days * 86_400_000L)
        return all().filter { it.day >= cutoff }
    }

    fun clear() = synchronized(lock) { file.delete() }

    // ---- persistence -----------------------------------------------------

    private fun keyOf(day: String, provider: String, model: String, kind: UsageEvent.Kind) =
        "$day|$provider|$model|${kind.name}"

    private fun read(): Map<String, DayUsage> {
        if (!file.exists()) return emptyMap()
        return try {
            val o = JSONObject(file.readText())
            buildMap {
                o.keys().forEach { k ->
                    val r = o.optJSONObject(k) ?: return@forEach
                    val kind = runCatching {
                        UsageEvent.Kind.valueOf(r.optString("kind"))
                    }.getOrNull() ?: return@forEach
                    put(
                        k,
                        DayUsage(
                            day = r.optString("day"),
                            provider = r.optString("provider"),
                            model = r.optString("model"),
                            kind = kind,
                            calls = r.optLong("calls"),
                            audioSeconds = r.optLong("audioSeconds"),
                            promptTokens = r.optLong("promptTokens"),
                            completionTokens = r.optLong("completionTokens"),
                        ),
                    )
                }
            }
        } catch (e: Exception) {
            // Usage is a convenience, never worth crashing over. A damaged
            // file reads as empty and is overwritten by the next call.
            emptyMap()
        }
    }

    private fun write(items: Map<String, DayUsage>) {
        val o = JSONObject()
        items.forEach { (k, v) ->
            o.put(
                k,
                JSONObject().apply {
                    put("day", v.day)
                    put("provider", v.provider)
                    put("model", v.model)
                    put("kind", v.kind.name)
                    put("calls", v.calls)
                    put("audioSeconds", v.audioSeconds)
                    put("promptTokens", v.promptTokens)
                    put("completionTokens", v.completionTokens)
                },
            )
        }
        val tmp = File(file.parentFile, "usage.json.tmp")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(o.toString().toByteArray())
                out.flush()
                out.fd.sync()
            }
            if (!tmp.renameTo(file)) tmp.delete()
        } catch (e: Exception) {
            tmp.delete()
        }
    }

    companion object {

        /**
         * Reads a provider's `usage` block, which both OpenAI and Mistral
         * return in the same shape.
         */
        fun eventFrom(
            json: org.json.JSONObject,
            provider: String,
            model: String,
            kind: UsageEvent.Kind,
        ): UsageEvent {
            val u = json.optJSONObject("usage")
            return UsageEvent(
                day = dayKey(),
                provider = provider,
                model = model,
                kind = kind,
                // Mistral names it prompt_audio_seconds; OpenAI reports
                // duration in seconds. Either way a missing value is 0 and
                // the call still counts.
                audioSeconds = u?.optLong("prompt_audio_seconds")
                    ?: u?.optLong("seconds") ?: 0L,
                promptTokens = u?.optLong("prompt_tokens")
                    ?: u?.optLong("input_tokens") ?: 0L,
                completionTokens = u?.optLong("completion_tokens")
                    ?: u?.optLong("output_tokens") ?: 0L,
            )
        }
        /**
         * The day a timestamp belongs to, in the device's own timezone --
         * "today" should mean the user's today, not UTC's.
         */
        fun dayKey(ms: Long = System.currentTimeMillis()): String =
            SimpleDateFormat("yyyy-MM-dd", Locale.US)
                .apply { timeZone = TimeZone.getDefault() }
                .format(Date(ms))
    }
}
