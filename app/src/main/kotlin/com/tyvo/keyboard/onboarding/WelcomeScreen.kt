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
package com.tyvo.keyboard.onboarding

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings as AndroidSettings
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.tyvo.keyboard.data.Provider
import com.tyvo.keyboard.data.Settings

/**
 * First run.
 *
 * Deliberately short: what the keyboard does, then only the steps without
 * which it cannot work at all. Everything else -- models, corrections,
 * language, retention -- has a sensible default and belongs in settings,
 * where it can be found once the thing is actually working.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WelcomeScreen(
    settings: Settings,
    onDone: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val ctx = LocalContext.current

    var provider by remember { mutableStateOf(settings.provider) }
    var apiKey by remember(provider) { mutableStateOf(settings.key(provider)) }
    var showKey by remember { mutableStateOf(false) }
    var tryText by remember { mutableStateOf("") }

    var micGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { micGranted = it }

    // Re-read on every recomposition: the user leaves for system settings to
    // enable the keyboard and comes back, and the row has to notice.
    var imeEnabled by remember { mutableStateOf(isImeEnabled(ctx)) }
    LaunchedEffect(Unit) { imeEnabled = isImeEnabled(ctx) }

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Tyvo", style = MaterialTheme.typography.displaySmall)
        Text(
            "Type with your voice.",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )

        Text(
            "Speak into any text field. Tyvo writes what you meant, not what " +
                "you said.",
            style = MaterialTheme.typography.bodyMedium,
        )

        ExampleCard()

        Text(
            "Then reshape it in one tap: shorter, formal, a list. Undo is " +
                "always one tap back.",
            style = MaterialTheme.typography.bodyMedium,
        )

        HorizontalDivider()

        Text("To get started", style = MaterialTheme.typography.titleMedium)

        // --- 1. key ------------------------------------------------------
        StepCard(number = 1, title = "Add an API key", done = apiKey.isNotBlank()) {
            Text(
                "Your own account, billed per use. One key covers both stages.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Wrapping, not a Row: four chips including "Google Gemini" do not
            // fit on one line, and a plain Row overflows rather than wrapping,
            // leaving a tall band of empty space below the squeezed chips.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Provider.entries.forEach { p ->
                    FilterChip(
                        selected = provider == p,
                        onClick = { provider = p; settings.provider = p },
                        label = { Text(p.label) },
                    )
                }
            }
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it; settings.setKey(provider, it) },
                label = { Text("${provider.label} API key") },
                singleLine = true,
                visualTransformation =
                    if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                textStyle = MaterialTheme.typography.bodyMedium
                    .copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = showKey, onCheckedChange = { showKey = it })
                Text("Show key", style = MaterialTheme.typography.bodySmall)
            }
        }

        // --- 2. microphone ------------------------------------------------
        StepCard(number = 2, title = "Allow the microphone", done = micGranted) {
            Text(
                "Audio goes to ${provider.label} and nowhere else.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!micGranted) {
                Button(onClick = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) }) {
                    Text("Allow microphone")
                }
            }
        }

        // --- 3. enable the keyboard ---------------------------------------
        StepCard(number = 3, title = "Turn Tyvo on", done = imeEnabled) {
            Text(
                "Enable it in Android's keyboard settings, then pick it with " +
                    "the globe key.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Stacked, not side by side: they are two sequential steps, and
            // on a narrow screen the pair wrapped awkwardly.
            OutlinedButton(
                onClick = {
                    ctx.startActivity(Intent(AndroidSettings.ACTION_INPUT_METHOD_SETTINGS))
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (imeEnabled) "Keyboard settings" else "Enable Tyvo") }

            if (imeEnabled) {
                OutlinedButton(
                    onClick = {
                        (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                            .showInputMethodPicker()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Switch to Tyvo") }
            }
        }

        // --- 4. try it ------------------------------------------------------
        // A real text field rather than a "Start dictating" button, which
        // could only ever close this screen -- it could not start anything,
        // since dictation happens in whatever app you are actually typing in.
        StepCard(number = 4, title = "Try it", done = tryText.isNotBlank()) {
            Text(
                "Switch to Tyvo with the globe key and hold the mic.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = tryText,
                onValueChange = { tryText = it },
                placeholder = { Text("Say something here") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )
        }

        Spacer(Modifier.height(4.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = { settings.hasOnboarded = true; onOpenSettings() },
                modifier = Modifier.weight(1f),
            ) { Text("More settings") }

            Button(
                onClick = { settings.hasOnboarded = true; onDone() },
                modifier = Modifier.weight(1f),
            ) { Text("Done") }
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ExampleCard() {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "You say",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "\"um so I am going to cook pasta sorry no lasagna\"",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Tyvo writes",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "I am going to cook lasagna.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun StepCard(
    number: Int,
    title: String,
    done: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (done) "✓" else "$number",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (done) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(28.dp),
                )
                Text(title, style = MaterialTheme.typography.titleSmall)
            }
            content()
        }
    }
}

private fun isImeEnabled(ctx: Context): Boolean {
    val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    return imm.enabledInputMethodList.any { it.packageName == ctx.packageName }
}
