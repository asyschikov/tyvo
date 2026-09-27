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
