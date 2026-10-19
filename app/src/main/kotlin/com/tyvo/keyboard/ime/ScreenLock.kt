package com.tyvo.keyboard.ime

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat

/**
 * Calls [onOff] when the screen turns off, so a recording can be finished
 * properly instead of dying with the keyboard window. Returns the receiver,
 * for unregistering.
 */
fun Context.onScreenOff(onOff: () -> Unit): BroadcastReceiver {
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = onOff()
    }
    ContextCompat.registerReceiver(
        this,
        receiver,
        IntentFilter(Intent.ACTION_SCREEN_OFF),
        ContextCompat.RECEIVER_NOT_EXPORTED,
    )
    return receiver
}

/** The lock screen is up, so no app's field is there to write into. */
fun Context.isLocked(): Boolean =
    (getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked
