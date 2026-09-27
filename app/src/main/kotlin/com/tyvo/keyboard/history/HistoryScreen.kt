package com.tyvo.keyboard.history

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

/**
 * Everything the user has dictated.
 *
 * Failed and pending recordings are pinned above the rest: they are the only
 * rows with work left to do, and a transcript the user never got back is
 * exactly the thing that must not scroll out of sight.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    recordings: List<Recording>,
    busyId: String?,
    onRetry: (Recording) -> Unit,
    onExport: (Recording) -> Unit,
    onCopy: (Recording) -> Unit,
    onDelete: (Recording) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val attention = recordings.count { it.needsAttention }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tyvo") },
                actions = {
                    TextButton(onClick = onOpenSettings) { Text("Settings") }
                },
            )
        },
    ) { padding ->
        if (recordings.isEmpty()) {
            Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                // The empty state is the first thing most people see after
                // onboarding, so it says what to do rather than just that
                // there is nothing here.
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(32.dp),
                ) {
                    Text(
                        "Nothing dictated yet",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "Open any app, tap a text field, switch to Tyvo with " +
                            "the globe key and hold the mic. Everything you " +
                            "dictate shows up here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            return@Scaffold
        }

        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (attention > 0) {
                item {
                    Text(
                        if (attention == 1) "1 recording needs attention"
                        else "$attention recordings need attention",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            items(recordings, key = { it.id }) { rec ->
                RecordingRow(
                    rec = rec,
                    busy = rec.id == busyId,
                    onRetry = { onRetry(rec) },
                    onExport = { onExport(rec) },
                    onCopy = { onCopy(rec) },
                    onDelete = { onDelete(rec) },
                )
            }
        }
    }
}

@Composable
private fun RecordingRow(
    rec: Recording,
    busy: Boolean,
    onRetry: () -> Unit,
    onExport: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = if (rec.needsAttention) {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            )
        } else CardDefaults.cardColors(),
    ) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    when (rec.status) {
                        Recording.Status.DONE -> "Transcribed"
                        Recording.Status.FAILED -> "Failed"
                        Recording.Status.PENDING -> "Not transcribed"
                    },
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    "${timestamp(rec.createdAt)}  ·  ${duration(rec.durationMs)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            rec.text?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            rec.error?.let {
                Text(
                    if (rec.attempts > 1) "$it  (${rec.attempts} attempts)" else it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }

            if (busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (rec.hasAudio) {
                        TextButton(onClick = onRetry) { Text("Retry") }
                        TextButton(onClick = onExport) { Text("Save audio") }
                    }
                    if (!rec.text.isNullOrBlank()) {
                        TextButton(onClick = onCopy) { Text("Copy") }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDelete) { Text("Delete") }
                }
            }
        }
    }
}

private fun timestamp(ms: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(ms))

private fun duration(ms: Long): String {
    if (ms <= 0) return "—"
    val s = ms / 1000
    return if (s < 60) "${s}s" else "%d:%02d".format(s / 60, s % 60)
}
