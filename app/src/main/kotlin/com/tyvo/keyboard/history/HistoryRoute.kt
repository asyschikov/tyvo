package com.tyvo.keyboard.history

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.tyvo.keyboard.data.Settings
import com.tyvo.keyboard.net.TyvoException
import com.tyvo.keyboard.polish.Polisher
import com.tyvo.keyboard.transcribe.Transcriber
import com.tyvo.keyboard.usage.UsageStore
import kotlinx.coroutines.launch

/**
 * Stateful wrapper around [HistoryScreen]: retrying, exporting and deleting.
 *
 * Retrying here rather than only in the keyboard matters because the usual
 * reason a transcription failed is no connection, and the user is most likely
 * to deal with it later, from the app, once they have signal again.
 */
@Composable
fun HistoryRoute(
    store: RecordingStore,
    settings: Settings,
    onOpenSettings: () -> Unit,
    onOpenUsage: () -> Unit,
) {
    val context = LocalContext.current
    val usage = remember(context) { UsageStore(context) }
    val scope = rememberCoroutineScope()

    var recordings by remember { mutableStateOf(store.all()) }
    var busyId by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        recordings = store.all()
        // Keep the notification in step: clearing the last failure should
        // clear the badge too.
        Notifications.transcriptionFailed(context, store.needingAttention().size)
    }

    HistoryScreen(
        recordings = recordings,
        busyId = busyId,
        onRetry = { rec ->
            val audio = store.audioFor(rec)
            if (audio == null) {
                toast(context, "Audio is no longer available.")
                return@HistoryScreen
            }
            busyId = rec.id
            scope.launch {
                try {
                    val text = Transcriber(settings, usage).transcribe(audio)
                    if (text.isBlank()) {
                        store.markFailed(rec.id, "No speech detected.")
                    } else {
                        val finished = if (settings.polishEnabled) {
                            runCatching { Polisher(settings, usage).cleanUp(text) }.getOrDefault(text)
                        } else text
                        store.markDone(rec.id, finished)
                        toast(context, "Transcribed.")
                    }
                } catch (e: TyvoException) {
                    store.markFailed(rec.id, e.message ?: "Transcription failed.")
                    toast(context, e.message ?: "Transcription failed.")
                } catch (e: Exception) {
                    store.markFailed(rec.id, "Transcription failed.")
                    toast(context, "Transcription failed.")
                } finally {
                    busyId = null
                    refresh()
                }
            }
        },
        onExport = { rec ->
            val audio = store.audioFor(rec)
            if (audio == null) {
                toast(context, "Audio is no longer available.")
            } else {
                shareAudio(context, audio)
            }
        },
        onCopy = { rec ->
            val text = rec.text.orEmpty()
            if (text.isBlank()) return@HistoryScreen
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Tyvo transcript", text))
            toast(context, "Copied.")
        },
        onDelete = { rec ->
            store.delete(rec.id)
            refresh()
        },
        onOpenSettings = onOpenSettings,
        onOpenUsage = onOpenUsage,
    )
}

/**
 * Hands the WAV to whatever the user picks -- Files, Drive, a messaging app.
 *
 * A share sheet rather than a direct write: the sandbox blocks writing into
 * arbitrary directories anyway, and this lets the file go wherever the user
 * actually wants it.
 */
private fun shareAudio(context: Context, file: java.io.File) {
    val uri: Uri = try {
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    } catch (e: Exception) {
        toast(context, "Could not share this recording.")
        return
    }
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "audio/wav"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Save recording"))
}

private fun toast(context: Context, message: String) {
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}
