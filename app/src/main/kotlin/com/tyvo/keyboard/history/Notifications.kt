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

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.tyvo.keyboard.R
import com.tyvo.keyboard.settings.SettingsActivity

/**
 * Tells the user a dictation could not be transcribed and is waiting in the
 * app.
 *
 * A notification rather than a toast: an IME is not a foreground activity, and
 * a toast would vanish before someone mid-sentence in another app noticed it.
 * This stays in the shade until it is dealt with.
 */
object Notifications {

    private const val CHANNEL_ID = "tyvo_failures"
    private const val NOTIFICATION_ID = 1001

    fun transcriptionFailed(context: Context, pendingCount: Int) {
        if (pendingCount <= 0) {
            clear(context)
            return
        }
        ensureChannel(context)

        val intent = Intent(context, SettingsActivity::class.java)
            .setAction(SettingsActivity.ACTION_SHOW_HISTORY)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val body = if (pendingCount == 1) {
            "Your recording was saved. Open Tyvo to retry it."
        } else {
            "$pendingCount recordings saved. Open Tyvo to retry them."
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Dictation could not be transcribed")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        // Posting without permission throws on API 33+; a missing notification
        // must never take down the keyboard.
        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }
    }

    fun clear(context: Context) {
        runCatching {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Failed dictations",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Recordings that could not be transcribed and can be retried."
            }
        )
    }
}
