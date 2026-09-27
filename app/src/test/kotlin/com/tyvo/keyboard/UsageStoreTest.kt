package com.tyvo.keyboard

import com.tyvo.keyboard.usage.UsageEvent
import com.tyvo.keyboard.usage.UsageStore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UsageStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var store: UsageStore

    @Before
    fun setUp() {
        store = UsageStore(tmp.newFolder())
    }

    private fun event(
        day: String = "2026-09-27",
        model: String = "voxtral-mini-latest",
        kind: UsageEvent.Kind = UsageEvent.Kind.TRANSCRIBE,
        audio: Long = 0,
        prompt: Long = 0,
        completion: Long = 0,
    ) = UsageEvent(day, "Mistral", model, kind, 1, audio, prompt, completion)

    @Test
    fun `calls on the same day and model accumulate into one row`() {
        store.record(event(audio = 4, prompt = 3, completion = 15))
        store.record(event(audio = 6, prompt = 5, completion = 20))

        val row = store.all().single()
        assertEquals(2, row.calls)
        assertEquals(10, row.audioSeconds)
        assertEquals(8, row.promptTokens)
        assertEquals(35, row.completionTokens)
        assertEquals(43, row.totalTokens)
    }

    @Test
    fun `different models are kept apart`() {
        // The point of the screen is comparing what each model costs, so
        // merging them would defeat it.
        store.record(event(model = "voxtral-mini-latest"))
        store.record(event(model = "ministral-3b-latest", kind = UsageEvent.Kind.POLISH))
        assertEquals(2, store.all().size)
    }

    @Test
    fun `transcription and clean-up on one model stay separate`() {
        store.record(event(model = "m", kind = UsageEvent.Kind.TRANSCRIBE))
        store.record(event(model = "m", kind = UsageEvent.Kind.POLISH))
        assertEquals(2, store.all().size)
    }

    @Test
    fun `days are kept apart and sorted newest first`() {
        store.record(event(day = "2026-09-25"))
        store.record(event(day = "2026-09-27"))
        store.record(event(day = "2026-09-26"))
        assertEquals(
            listOf("2026-09-27", "2026-09-26", "2026-09-25"),
            store.all().map { it.day },
        )
    }

    @Test
    fun `usage survives a reopen`() {
        val dir = tmp.newFolder()
        UsageStore(dir).record(event(audio = 12))
        assertEquals(12, UsageStore(dir).all().single().audioSeconds)
    }

    @Test
    fun `a damaged file reads as empty rather than throwing`() {
        val dir = tmp.newFolder()
        UsageStore(dir).record(event())
        java.io.File(dir, "usage.json").writeText("{ not json")
        assertTrue(UsageStore(dir).all().isEmpty())
    }

    @Test
    fun `clear empties the counters`() {
        store.record(event(audio = 5))
        store.clear()
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun `usage is parsed from a mistral transcription response`() {
        val json = JSONObject(
            """
            {"usage":{"prompt_audio_seconds":4,"prompt_tokens":3,
             "completion_tokens":15,"total_tokens":393}}
            """.trimIndent()
        )
        val e = UsageStore.eventFrom(json, "Mistral", "voxtral-mini-latest", UsageEvent.Kind.TRANSCRIBE)
        assertEquals(4, e.audioSeconds)
        assertEquals(3, e.promptTokens)
        assertEquals(15, e.completionTokens)
    }

    @Test
    fun `a response with no usage block still counts the call`() {
        val e = UsageStore.eventFrom(JSONObject("{}"), "Mistral", "m", UsageEvent.Kind.POLISH)
        assertEquals(1, e.calls)
        assertEquals(0, e.totalTokens)
    }

    @Test
    fun `day keys sort chronologically as strings`() {
        // recent() relies on lexical comparison, so the format has to sort.
        val now = System.currentTimeMillis()
        val earlier = UsageStore.dayKey(now - 2 * 86_400_000L)
        assertTrue(earlier < UsageStore.dayKey(now))
    }
}
