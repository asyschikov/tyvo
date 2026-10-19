package com.tyvo.keyboard.bubble

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings as AndroidSettings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/** Whether the user has switched the floating mic on in Accessibility settings. */
fun isBubbleEnabled(ctx: Context): Boolean {
    val enabled = AndroidSettings.Secure.getString(
        ctx.contentResolver,
        AndroidSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ) ?: return false
    val ours = ComponentName(ctx, BubbleService::class.java)
    return enabled.split(':').any { ComponentName.unflattenFromString(it) == ours }
}

/**
 * Says what the floating mic does with accessibility, then sends the user to
 * the switch.
 *
 * Every way in goes through this -- the app's Settings, its top bar, the
 * keyboard -- because accessibility is a lot to ask for, and Play requires
 * the explanation before the request.
 */
@Composable
fun BubbleSetupDialog(ctx: Context, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Bubble") },
        text = {
            Text(
                "Show the Tyvo bubble whenever you type, with any keyboard. " +
                    "Tap it to dictate, hold it to edit what you dictated.\n\n" +
                    "Tyvo never saves or sends the text already in your fields, " +
                    "and skips passwords.\n\n" +
                    "Next: turn on Tyvo bubble."
            )
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                ctx.startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS))
            }) { Text("Open settings") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Not now") }
        },
    )
}
