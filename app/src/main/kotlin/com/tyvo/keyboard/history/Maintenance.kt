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

import com.tyvo.keyboard.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Background housekeeping, run whenever the app or the keyboard starts.
 *
 * Both entry points do it because either can be the only one a user opens for
 * days: someone who dictates constantly may never open the app, and someone
 * reviewing history may not have used the keyboard since.
 */
object Maintenance {

    /**
     * Recovers any audio the index lost track of, then expires audio older
     * than a day. Recovery runs first so a just-recovered file is subject to
     * the same expiry rules as everything else.
     *
     * Off the main thread: it touches the filesystem, and on the IME path the
     * main thread is drawing the keyboard.
     */
    fun runInBackground(store: RecordingStore, settings: Settings) {
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                store.recoverOrphanedAudio()
                store.expireAudio(keepFailed = settings.keepFailedAudio)
            }
        }
    }
}
