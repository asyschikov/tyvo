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
