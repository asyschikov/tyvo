package com.tyvo.keyboard.usage

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What the user has spent, per day and model.
 *
 * Numbers are the providers' own, reported back from each call's `usage`
 * block, so they can be checked against a bill. No prices are shown: they
 * change without notice and per account, and a stale figure presented as
 * fact would be worse than none.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageScreen(
    rows: List<DayUsage>,
    onClear: () -> Unit,
    onBack: () -> Unit,
) {
    var confirmClear by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Usage") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                actions = {
                    if (rows.isNotEmpty()) {
                        TextButton(onClick = { confirmClear = true }) { Text("Reset") }
                    }
                },
            )
        },
    ) { padding ->
        if (rows.isEmpty()) {
            Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Nothing used yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }

        val byDay = rows.groupBy { it.day }
        val totalAudio = rows.sumOf { it.audioSeconds }
        val totalTokens = rows.sumOf { it.totalTokens }
        val totalCalls = rows.sumOf { it.calls }

        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("All time", style = MaterialTheme.typography.titleMedium)
                        StatRow("Audio transcribed", duration(totalAudio))
                        StatRow("Tokens", compact(totalTokens))
                        StatRow("API calls", totalCalls.toString())
                    }
                }
            }

            byDay.forEach { (day, dayRows) ->
                item {
                    Text(
                        friendlyDay(day),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                items(dayRows, key = { "${it.day}|${it.model}|${it.kind}" }) { row ->
                    ModelRow(row)
                }
            }

            item {
                Text(
                    "Counts come from each provider's own response, so they can " +
                        "be checked against your bill. Prices are not shown " +
                        "because they change per account and go stale.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Reset usage?") },
            text = {
                Text(
                    "Clears the counters on this screen. Your recordings and " +
                        "transcripts are not affected."
                )
            },
            confirmButton = {
                TextButton(onClick = { onClear(); confirmClear = false }) { Text("Reset") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ModelRow(row: DayUsage) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    row.model,
                    style = MaterialTheme.typography.bodyMedium
                        .copy(fontFamily = FontFamily.Monospace),
                )
                Text(
                    when (row.kind) {
                        UsageEvent.Kind.TRANSCRIBE -> "Transcription"
                        UsageEvent.Kind.POLISH -> "Clean-up"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (row.audioSeconds > 0) {
                StatRow("Audio", duration(row.audioSeconds))
            }
            if (row.totalTokens > 0) {
                StatRow(
                    "Tokens",
                    "${compact(row.totalTokens)}  " +
                        "(${compact(row.promptTokens)} in, ${compact(row.completionTokens)} out)",
                )
            }
            StatRow("Calls", row.calls.toString())
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

private fun duration(seconds: Long): String = when {
    seconds <= 0 -> "—"
    seconds < 60 -> "${seconds}s"
    seconds < 3600 -> "%d min %02ds".format(seconds / 60, seconds % 60)
    else -> "%dh %02dm".format(seconds / 3600, (seconds % 3600) / 60)
}

/** Thousands separators are noise at a glance; 12.3k reads faster. */
private fun compact(n: Long): String = when {
    n < 1_000 -> n.toString()
    n < 1_000_000 -> "%.1fk".format(n / 1_000.0)
    else -> "%.2fM".format(n / 1_000_000.0)
}

private fun friendlyDay(day: String): String {
    val today = UsageStore.dayKey()
    val yesterday = UsageStore.dayKey(System.currentTimeMillis() - 86_400_000L)
    return when (day) {
        today -> "Today"
        yesterday -> "Yesterday"
        else -> runCatching {
            val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(day)
            SimpleDateFormat("d MMMM", Locale.getDefault()).format(parsed ?: Date())
        }.getOrDefault(day)
    }
}
