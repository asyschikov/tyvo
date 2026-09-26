package com.tyvo.keyboard.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
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
import com.tyvo.keyboard.data.ModelCatalog
import com.tyvo.keyboard.data.ModelOption
import com.tyvo.keyboard.data.Readiness
import com.tyvo.keyboard.data.Provider
import com.tyvo.keyboard.net.ConnectionTest
import kotlinx.coroutines.launch
import com.tyvo.keyboard.polish.Polisher
import com.tyvo.keyboard.transcribe.Transcriber

class SettingsActivity : ComponentActivity() {

    private lateinit var settings: Settings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SettingsScreen(settings, this)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(settings: Settings, activity: ComponentActivity) {
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
    var vocab by remember { mutableStateOf(settings.vocabulary) }
    var showKey by remember { mutableStateOf(false) }
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

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Tyvo", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Voice keyboard with LLM clean-up.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

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
                "One provider handles both transcription and clean-up, so you " +
                    "only ever need a single API key.",
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
                "Stored encrypted on this device and sent only to " +
                    "${provider.label}. Keys for other providers are kept, so " +
                    "switching back does not mean pasting it again.",
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
        }

        // ---- models -------------------------------------------------------
        SectionCard("Models") {
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
                "Clean-up — applies spoken corrections (\"book pasta, sorry no, " +
                    "lasagna\"), removes filler, and punctuates.",
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
                            "Off: raw transcript is inserted; clean-up stays one tap away.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // ---- accuracy -----------------------------------------------------
        SectionCard("Accuracy") {
            OutlinedTextField(
                value = lang,
                onValueChange = { lang = it; settings.languageHint = it },
                label = { Text("Language hint") },
                placeholder = { Text("blank = auto-detect, e.g. en, ru, de") },
                singleLine = true,
                keyboardOptions = KeyboardOptions.Default,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = vocab,
                onValueChange = { vocab = it; settings.vocabulary = it },
                label = { Text("Vocabulary") },
                placeholder = { Text("Names and jargon, comma separated") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )
            Text(
                "Words the transcriber keeps getting wrong: product names, " +
                    "colleagues, technical terms.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(24.dp))
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
