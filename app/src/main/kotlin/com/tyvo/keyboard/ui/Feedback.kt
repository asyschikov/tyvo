package com.tyvo.keyboard.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tyvo.keyboard.BuildConfig

/**
 * A Google Form, and the entry ids of the setup questions it shares with
 * the app. Each form numbers its own questions, so the ids are per form.
 * The /d/<id>/viewform link redirects to the published form and keeps the
 * pre-filled answers.
 */
private class FeedbackForm(
    val id: String,
    val version: Long,
    val android: Long,
    val manufacturer: Long,
)

private val BUG_REPORT = FeedbackForm(
    id = "1b3lZvpWDCZQ2KqDI7KQoPvsjQpTRwoNk63MeAdgU_GQ",
    version = 487671056,
    android = 1764594464,
    manufacturer = 1787080616,
)

private val FEEDBACK = FeedbackForm(
    id = "16hSd9Hpq2vHe4jGMxEM6DBA_QzJZuFqy-IPEOziH-MU",
    version = 487671056,
    android = 1660114440,
    manufacturer = 46482297,
)

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
fun BoxScope.FeedbackButton() {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    Box(
        Modifier
            .align(Alignment.BottomEnd)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(20.dp),
    ) {
        FloatingActionButton(onClick = { open = true }) {
            Icon(Icons.Outlined.MailOutline, contentDescription = "Send feedback")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Submit bug report") },
                onClick = { open = false; openForm(ctx, BUG_REPORT) },
            )
            DropdownMenuItem(
                text = { Text("Submit feedback") },
                onClick = { open = false; openForm(ctx, FEEDBACK) },
            )
        }
    }
}

/**
 * Opens [form] in the browser with the setup details already answered --
 * app version, Android version, phone manufacturer -- because asking for
 * them afterwards costs a round trip. Nothing dictated is included: the
 * transcripts are the private part, and a feedback button is no place to
 * leak them.
 */
private fun openForm(ctx: Context, form: FeedbackForm) {
    val uri = Uri.parse("https://docs.google.com/forms/d/${form.id}/viewform").buildUpon()
        .appendQueryParameter("usp", "pp_url")
        .appendQueryParameter(
            "entry.${form.version}",
            "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
        )
        .appendQueryParameter(
            "entry.${form.android}",
            "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        )
        .appendQueryParameter("entry.${form.manufacturer}", Build.MANUFACTURER)
        .build()
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, uri))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(ctx, "No browser found to open the form", Toast.LENGTH_LONG).show()
    }
}
