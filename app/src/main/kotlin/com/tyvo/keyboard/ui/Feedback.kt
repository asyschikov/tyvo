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
package com.tyvo.keyboard.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tyvo.keyboard.BuildConfig
import com.tyvo.keyboard.data.Settings
import com.tyvo.keyboard.polish.Polisher
import com.tyvo.keyboard.transcribe.Transcriber

const val FEEDBACK_ADDRESS = "asyschikov+tyvo@gmail.com"

/**
 * The feedback button, bottom-right of whichever screen is showing.
 *
 * On every screen rather than buried in settings: the moment someone has
 * something to say is the moment something went wrong, and that is rarely
 * while they happen to be looking at the settings form.
 *
 * Call inside a [androidx.compose.foundation.layout.Box] that fills the
 * screen.
 */
@Composable
fun BoxScope.FeedbackButton(settings: Settings) {
    val ctx = LocalContext.current
    FloatingActionButton(
        onClick = { sendFeedback(ctx, settings) },
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(20.dp),
    ) {
        Icon(Icons.Outlined.MailOutline, contentDescription = "Send feedback")
    }
}

/**
 * Opens a pre-addressed draft in whatever mail app the user has.
 *
 * Pre-fills the setup details that make a bug report answerable -- app
 * version, Android version, device, provider and models -- because asking
 * for them afterwards costs a round trip. Nothing dictated is included: the
 * transcripts are the private part, and a feedback button is no place to
 * leak them.
 */
fun sendFeedback(ctx: Context, settings: Settings) {
    val provider = settings.provider
    val body = buildString {
        append("\n\n\n---\n")
        append("Tyvo ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n")
        append("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
        append("${Build.MANUFACTURER} ${Build.MODEL}\n")
        append("Provider: ${provider.label}\n")
        append(
            "Transcription: " +
                settings.transcribeModel(provider)
                    .ifBlank { Transcriber.defaultModelFor(provider) } + "\n"
        )
        append(
            "Clean-up: " +
                if (!settings.polishEnabled) "off"
                else settings.polishModel(provider)
                    .ifBlank { Polisher.defaultModelFor(provider) }
        )
    }

    val intent = Intent(Intent.ACTION_SENDTO).apply {
        // SENDTO with a mailto: URI reaches mail apps only, so the chooser
        // is not cluttered with every app that accepts plain text.
        data = Uri.parse("mailto:")
        putExtra(Intent.EXTRA_EMAIL, arrayOf(FEEDBACK_ADDRESS))
        putExtra(Intent.EXTRA_SUBJECT, "Tyvo feedback")
        putExtra(Intent.EXTRA_TEXT, body)
    }

    try {
        ctx.startActivity(Intent.createChooser(intent, "Send feedback"))
    } catch (e: ActivityNotFoundException) {
        // No mail app: leave the address somewhere the user can use it.
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Tyvo feedback address", FEEDBACK_ADDRESS))
        Toast.makeText(
            ctx,
            "No mail app found. Address copied: $FEEDBACK_ADDRESS",
            Toast.LENGTH_LONG,
        ).show()
    }
}
