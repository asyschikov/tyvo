package com.tyvo.keyboard.net

import com.tyvo.keyboard.data.Provider
import com.tyvo.keyboard.data.Settings
import com.tyvo.keyboard.polish.Polisher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Sends one trivial request per provider so a bad key or a wrong model name
 * is caught in settings rather than three seconds into a dictation.
 */
object ConnectionTest {

    sealed interface Result {
        data class Ok(val detail: String) : Result
        data class Failed(val reason: String) : Result
    }

    /**
     * Round-trips a short string through the polish path. Works for all three
     * providers, since every one of them serves text in, text out.
     */
    suspend fun test(settings: Settings, provider: Provider): Result =
        withContext(Dispatchers.IO) {
            if (settings.key(provider).isBlank()) {
                return@withContext Result.Failed("No ${provider.label} key set.")
            }
            try {
                val out = Polisher(settings).transform(
                    text = "hello  world",
                    instruction = "Fix spacing only.",
                    provider = provider,
                )
                if (out.isBlank()) Result.Failed("Empty response.")
                else Result.Ok("OK — ${provider.label} responded.")
            } catch (e: TyvoException) {
                Result.Failed(e.message ?: "Failed.")
            } catch (e: Exception) {
                Result.Failed("Failed.")
            }
        }
}
