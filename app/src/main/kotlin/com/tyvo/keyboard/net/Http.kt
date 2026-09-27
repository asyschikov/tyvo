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

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/** An error with a message short enough to show on the keyboard status line. */
class TyvoException(message: String, cause: Throwable? = null) : Exception(message, cause)

object Http {

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * Executes [req] and parses a JSON object response.
     *
     * Network and HTTP failures are converted into [TyvoException] with a
     * message fit for a one-line status area -- the raw provider error is
     * mined for its useful sentence rather than dumped verbatim.
     */
    fun json(req: Request, what: String): JSONObject {
        val response = try {
            client.newCall(req).execute()
        } catch (e: UnknownHostException) {
            throw TyvoException("No connection.", e)
        } catch (e: SocketTimeoutException) {
            throw TyvoException("$what timed out.", e)
        } catch (e: IOException) {
            throw TyvoException("Network error.", e)
        }

        response.use { r ->
            val text = try {
                r.body?.string().orEmpty()
            } catch (e: IOException) {
                throw TyvoException("$what: response unreadable.", e)
            }

            if (!r.isSuccessful) {
                throw TyvoException(friendlyError(r.code, text, what))
            }
            return try {
                JSONObject(text)
            } catch (e: Exception) {
                throw TyvoException("$what: unexpected response.", e)
            }
        }
    }

    /** Pulls the human-readable sentence out of a provider error envelope. */
    private fun friendlyError(code: Int, body: String, what: String): String {
        val detail = runCatching {
            val o = JSONObject(body)
            val err = o.opt("error")
            when (err) {
                is JSONObject -> err.optString("message").ifBlank { null }
                is String -> err.ifBlank { null }
                else -> o.optString("message").ifBlank { null }
                    ?: (o.opt("detail") as? String)
            }
        }.getOrNull()

        val base = when (code) {
            401, 403 -> "Bad or missing API key"
            404 -> "Model not found"
            413 -> "Recording too long"
            429 -> "Rate limited - wait a moment"
            in 500..599 -> "Provider unavailable"
            else -> "$what failed ($code)"
        }
        // Provider text is usually more specific than our generic label.
        return if (detail != null && detail.length < 140) "$base: $detail" else base
    }
}
