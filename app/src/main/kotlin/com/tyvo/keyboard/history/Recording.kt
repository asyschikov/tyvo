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
package com.tyvo.keyboard.history

/**
 * One dictation, kept so nothing the user said is ever lost.
 *
 * A failed transcription is the dangerous case: without the audio there is
 * nothing to retry and the words are simply gone. So a recording is written
 * to the store the moment capture finishes, before the network is involved,
 * and only reaches [Status.DONE] once text exists.
 */
data class Recording(
    val id: String,
    val createdAt: Long,
    val status: Status,
    /** Transcribed text, once there is any. */
    val text: String? = null,
    /** Filename of the WAV inside the store's audio directory, while kept. */
    val audioFile: String? = null,
    val durationMs: Long = 0,
    /** Why the last attempt failed, for display on a failed row. */
    val error: String? = null,
    val attempts: Int = 0,
) {
    enum class Status {
        /** Captured, not yet transcribed. Audio is on disk. */
        PENDING,

        /** Transcription failed. Audio is on disk and can be retried. */
        FAILED,

        /** Transcribed. Audio has been deleted; the text is what remains. */
        DONE,
    }

    val needsAttention: Boolean
        get() = status == Status.PENDING || status == Status.FAILED

    val hasAudio: Boolean get() = audioFile != null
}
