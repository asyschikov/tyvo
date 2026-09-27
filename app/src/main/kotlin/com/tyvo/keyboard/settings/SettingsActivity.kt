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
package com.tyvo.keyboard.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import android.provider.Settings as AndroidSettings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.tyvo.keyboard.data.Settings
import com.tyvo.keyboard.data.Languages
import com.tyvo.keyboard.data.ModelCatalog
import com.tyvo.keyboard.data.ModelOption
import com.tyvo.keyboard.data.Readiness
import androidx.activity.compose.BackHandler
import com.tyvo.keyboard.history.HistoryRoute
import com.tyvo.keyboard.onboarding.WelcomeScreen
import com.tyvo.keyboard.usage.UsageScreen
import com.tyvo.keyboard.usage.UsageStore
import com.tyvo.keyboard.history.Maintenance
import com.tyvo.keyboard.history.Notifications
import com.tyvo.keyboard.history.RecordingStore
import com.tyvo.keyboard.BuildConfig
import com.tyvo.keyboard.data.Provider
import com.tyvo.keyboard.net.ConnectionTest
import kotlinx.coroutines.launch
import com.tyvo.keyboard.polish.Correction
import com.tyvo.keyboard.polish.Polisher
import com.tyvo.keyboard.transcribe.Transcriber

/** Which of the app's screens is showing. */
private enum class Screen { WELCOME, HISTORY, SETTINGS, USAGE }

class SettingsActivity : ComponentActivity() {

    private lateinit var settings: Settings
    private lateinit var store: RecordingStore

    companion object {
        /** Deep link from the failed-transcription notification. */
        const val ACTION_SHOW_HISTORY = "com.tyvo.keyboard.SHOW_HISTORY"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        store = RecordingStore(this)
        Maintenance.runInBackground(store, settings)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    // History is home once the user is set up; the welcome
                    // flow only stands in front of it the first time.
                    var screen by remember {
                        // A notification tap means there is a recording
                        // waiting, so the welcome flow would be in the way
                        // even if onboarding was skipped earlier.
                        val fromNotification = intent?.action == ACTION_SHOW_HISTORY
                        mutableStateOf(
                            if (settings.hasOnboarded || fromNotification) Screen.HISTORY
                            else Screen.WELCOME
                        )
                    }

                    when (screen) {
                        Screen.WELCOME -> WelcomeScreen(
                            settings = settings,
                            onDone = { screen = Screen.HISTORY },
                        )

                        Screen.HISTORY -> HistoryRoute(
                            store = store,
                            settings = settings,
                            onOpenSettings = { screen = Screen.SETTINGS },
                            onOpenUsage = { screen = Screen.USAGE },
                        )

                        Screen.SETTINGS -> SettingsScreen(
                            settings = settings,
                            activity = this,
                            onBack = { screen = Screen.HISTORY },
                        )

                        Screen.USAGE -> {
                            val usageStore = remember { UsageStore(this) }
                            var rows by remember { mutableStateOf(usageStore.all()) }
                            UsageScreen(
                                rows = rows,
                                onClear = { usageStore.clear(); rows = usageStore.all() },
                                onBack = { screen = Screen.HISTORY },
                            )
                        }
                    }

                    BackHandler(
                        enabled = screen == Screen.SETTINGS || screen == Screen.USAGE
                    ) { screen = Screen.HISTORY }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    settings: Settings,
    activity: ComponentActivity,
    onBack: () -> Unit,
) {
    val ctx = activity as Context

    var provider by remember { mutableStateOf(settings.provider) }
    // Keyed on provider so switching swaps in that provider's own saved
    // values rather than carrying the previous one's across.
    var apiKey by remember(provider) { mutableStateOf(settings.key(provider)) }
    var tModel by remember(provider) { mutableStateOf(settings.transcribeModel(provider)) }
    var pModel by remember(provider) { mutableStateOf(settings.polishModel(provider)) }

    var polishOn by remember { mutableStateOf(settings.polishEnabled) }
    var autoPolish by remember { mutableStateOf(settings.autoPolish) }
    var lang by remember { mutableStateOf(settings.languageHint) }
    var pickingLanguage by remember { mutableStateOf(false) }
    var vocab by remember { mutableStateOf(settings.vocabulary) }
    var showKey by remember { mutableStateOf(false) }
    var notifyOnFail by remember { mutableStateOf(settings.failureNotifications) }
    var toastOnFail by remember { mutableStateOf(settings.failureToasts) }
    var keepFailed by remember { mutableStateOf(settings.keepFailedAudio) }
    var corrections by remember { mutableStateOf(settings.corrections()) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val readiness = remember(apiKey, provider) { Readiness.of(settings) }

    var micGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { micGranted = it }

    // Asked for alongside the mic so the failed-dictation notification can
    // actually be posted; refusing it costs the notification, nothing else.
    var notifyGranted by remember {
        mutableStateOf(
            android.os.Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(
                    ctx, android.Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
        )
    }
    val notifyLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { notifyGranted = it }

    Box(Modifier.fillMaxSize()) {
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Back") }
        }
        Text("Settings", style = MaterialTheme.typography.headlineMedium)

        readiness.problem?.let { problem ->
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Text(
                    problem,
                    Modifier.padding(14.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }

        // ---- setup ------------------------------------------------------
        SectionCard("Setup") {
            SetupRow(
                done = micGranted,
                label = "Microphone access",
                button = "Grant",
                onClick = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
            )
            if (android.os.Build.VERSION.SDK_INT >= 33 && notifyOnFail) {
                SetupRow(
                    done = notifyGranted,
                    label = "Alerts for failed dictations",
                    button = "Allow",
                    onClick = {
                        notifyLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    },
                )
            }
            SetupRow(
                done = isImeEnabled(ctx),
                label = "Enable Tyvo in system settings",
                button = "Open",
                onClick = {
                    ctx.startActivity(Intent(AndroidSettings.ACTION_INPUT_METHOD_SETTINGS))
                },
            )
            SetupRow(
                done = false,
                showCheck = false,
                label = "Switch to Tyvo",
                button = "Picker",
                onClick = {
                    (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                        .showInputMethodPicker()
                },
            )
        }

        // ---- provider ----------------------------------------------------
        SectionCard("AI provider") {
            Text(
                "One key covers both transcription and clean-up.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ChoiceRow(
                options = Provider.entries.map { it to it.label },
                selected = provider,
                onSelect = {
                    provider = it
                    settings.provider = it
                    testResult = null
                },
            )
        }

        // ---- everything for the selected provider -------------------------
        SectionCard(provider.label) {
            if (!provider.verified) {
                Text(
                    "Untested against the live API. OpenAI and Mistral are known " +
                        "to work.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
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
                Text("Show key", style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                "Stored encrypted, sent only to ${provider.label}. Keys for " +
                    "other providers are kept.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Button(
                    enabled = !testing && apiKey.isNotBlank(),
                    onClick = {
                        testing = true
                        testResult = null
                        scope.launch {
                            val r = ConnectionTest.test(settings, provider)
                            testResult = when (r) {
                                is ConnectionTest.Result.Ok -> r.detail
                                is ConnectionTest.Result.Failed -> r.reason
                            }
                            testing = false
                        }
                    },
                ) { Text(if (testing) "Testing…" else "Test connection") }
                Spacer(Modifier.width(12.dp))
                testResult?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }

            HorizontalDivider()

            Text(
                "Transcription — turns your voice into text.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ModelPicker(
                options = ModelCatalog.transcribeFor(provider),
                selected = tModel,
                default = Transcriber.defaultModelFor(provider),
                onSelect = { tModel = it; settings.setTranscribeModel(provider, it) },
            )

            Spacer(Modifier.height(4.dp))
            Text(
                "Clean-up — turns what you said into what you meant to write.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Switch(
                    checked = polishOn,
                    onCheckedChange = { polishOn = it; settings.polishEnabled = it },
                )
                Spacer(Modifier.width(10.dp))
                Text("Clean up dictation", style = MaterialTheme.typography.bodyMedium)
            }
            if (polishOn) {
                ModelPicker(
                    options = ModelCatalog.polishFor(provider),
                    selected = pModel,
                    default = Polisher.defaultModelFor(provider),
                    onSelect = { pModel = it; settings.setPolishModel(provider, it) },
                )

                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Corrections",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f),
                    )
                    val allOn = corrections.size == Correction.entries.size
                    TextButton(onClick = {
                        val next = if (allOn) emptySet() else Correction.entries.toSet()
                        corrections = next
                        settings.enabledCorrections = next.map { it.id }.toSet()
                    }) { Text(if (allOn) "None" else "All") }
                }
                Correction.entries.forEach { c ->
                    val on = c in corrections
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = on,
                            onCheckedChange = { checked ->
                                settings.setCorrection(c, checked)
                                corrections = settings.corrections()
                            },
                        )
                        Spacer(Modifier.width(6.dp))
                        Column {
                            Text(c.label, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                c.summary,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                // Measured, not guessed: on a small model, seven corrections
                // drops the spoken-correction hit rate from 3/3 to 1/3.
                val heavy = corrections.count { !it.defaultOn } > 0 &&
                    corrections.size > Correction.defaults.size
                if (heavy) {
                    Text(
                        "Extras crowd out the others on small models: measured " +
                            "on Ministral 3B, spoken corrections go from always " +
                            "applied to rarely.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (corrections.isEmpty()) {
                    Text(
                        "Nothing selected: the transcript is returned as spoken.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Switch(
                        checked = autoPolish,
                        onCheckedChange = { autoPolish = it; settings.autoPolish = it },
                    )
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            "Clean up automatically",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "Off: insert the raw transcript; clean up on demand.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // ---- accuracy -----------------------------------------------------
        // ---- language ------------------------------------------------------
        SectionCard("Language") {
            val chosen = Languages.byCode(lang)
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        chosen?.name ?: "Detect automatically",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        chosen?.native?.takeIf { it != chosen.name }
                            ?: "The provider works out what you are speaking.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (chosen != null) {
                    TextButton(onClick = { lang = ""; settings.languageHint = "" }) {
                        Text("Clear")
                    }
                }
                TextButton(onClick = { pickingLanguage = true }) {
                    Text(if (chosen == null) "Choose" else "Change")
                }
            }

            Text(
                "Only worth setting if you dictate in one language. OpenAI " +
                    "honours it; Mistral detects the language itself and appears " +
                    "to ignore it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (lang.isBlank()) {
                Text(
                    "Detection handled every case tested, down to single words.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ---- vocabulary ----------------------------------------------------
        SectionCard("Vocabulary") {
            OutlinedTextField(
                value = vocab,
                onValueChange = { vocab = it; settings.vocabulary = it },
                label = { Text("Words to expect") },
                placeholder = { Text("Names and jargon, comma separated") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )
            Text(
                "Names and jargon it keeps mishearing. A nudge, not a rule.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Leaves room for the floating button to sit over dead space
        // rather than over the last setting.
        Spacer(Modifier.height(72.dp))
    }

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

    if (pickingLanguage) {
        LanguagePickerDialog(
            alreadyChosen = emptyList(),
            onPick = {
                lang = it.code
                settings.languageHint = it.code
                pickingLanguage = false
            },
            onDismiss = { pickingLanguage = false },
        )
    }
}

/**
 * Model chooser: a dropdown of curated options plus a "Custom" escape hatch.
 *
 * An empty stored value means "use the provider default", which is shown as
 * the selected entry rather than as a blank field -- a blank model box reads
 * like something is unconfigured.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelPicker(
    options: List<ModelOption>,
    selected: String,
    default: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var custom by remember(selected) {
        mutableStateOf(selected.isNotBlank() && options.none { it.id == selected })
    }

    val effective = selected.ifBlank { default }
    val match = options.firstOrNull { it.id == effective }
    val display = when {
        custom -> "Custom"
        match != null -> match.label + if (selected.isBlank()) " (default)" else ""
        else -> effective
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
        ) {
            OutlinedTextField(
                value = display,
                onValueChange = {},
                readOnly = true,
                label = { Text("Model") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { opt ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    opt.label + if (opt.id == default) "  (default)" else "",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    opt.note,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        onClick = {
                            custom = false
                            expanded = false
                            // Storing blank for the default keeps the app on a
                            // sane model if we later change what that is.
                            onSelect(if (opt.id == default) "" else opt.id)
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text("Custom…", style = MaterialTheme.typography.bodyMedium) },
                    onClick = { custom = true; expanded = false },
                )
            }
        }

        if (custom) {
            OutlinedTextField(
                value = selected,
                onValueChange = onSelect,
                label = { Text("Model ID") },
                placeholder = { Text(default) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium
                    .copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth(),
            )
        } else if (match != null) {
            Text(
                match.note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
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
private fun sendFeedback(ctx: Context, settings: Settings) {
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
    } catch (e: android.content.ActivityNotFoundException) {
        // No mail app: leave the address somewhere the user can use it.
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(
            android.content.ClipData.newPlainText("Tyvo feedback address", FEEDBACK_ADDRESS)
        )
        Toast.makeText(
            ctx,
            "No mail app found. Address copied: $FEEDBACK_ADDRESS",
            Toast.LENGTH_LONG,
        ).show()
    }
}

private const val FEEDBACK_ADDRESS = "asyschikov+tyvo@gmail.com"

@Composable
private fun ToggleRow(
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    title: String,
    subtitle: String,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Switch(checked = checked, onCheckedChange = onChange)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SetupRow(
    done: Boolean,
    label: String,
    button: String,
    showCheck: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showCheck) {
            Text(if (done) "✓" else "○", Modifier.width(24.dp))
        } else {
            Spacer(Modifier.width(24.dp))
        }
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onClick) { Text(button) }
    }
}

@Composable
private fun KeyField(
    label: String,
    value: String,
    visible: Boolean,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation =
            if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun <T> ChoiceRow(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Column {
        options.forEach { (value, label) ->
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = value == selected, onClick = { onSelect(value) })
                Text(label, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private fun isImeEnabled(ctx: Context): Boolean {
    val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    return imm.enabledInputMethodList.any { it.packageName == ctx.packageName }
}
