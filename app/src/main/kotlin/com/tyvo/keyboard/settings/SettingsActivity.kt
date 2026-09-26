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
import com.tyvo.keyboard.data.PolishProvider
import com.tyvo.keyboard.data.Settings
import com.tyvo.keyboard.data.Readiness
import com.tyvo.keyboard.data.TranscribeProvider
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

    var anthropic by remember { mutableStateOf(settings.anthropicKey) }
    var openai by remember { mutableStateOf(settings.openAiKey) }
    var mistral by remember { mutableStateOf(settings.mistralKey) }
    var tProvider by remember { mutableStateOf(settings.transcribeProvider) }
    var pProvider by remember { mutableStateOf(settings.polishProvider) }
    var tModel by remember { mutableStateOf(settings.transcribeModel) }
    var pModel by remember { mutableStateOf(settings.polishModel) }
    var autoPolish by remember { mutableStateOf(settings.autoPolish) }
    var lang by remember { mutableStateOf(settings.languageHint) }
    var vocab by remember { mutableStateOf(settings.vocabulary) }
    var showKeys by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Recomputed on every relevant edit so the warning tracks the live config.
    val readiness = remember(anthropic, openai, mistral, tProvider, pProvider) {
        Readiness.of(settings)
    }

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

        // ---- keys -------------------------------------------------------
        SectionCard("API keys") {
            Text(
                "Stored encrypted on this device and sent only to the provider you pick.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            KeyField("Anthropic", anthropic, showKeys) {
                anthropic = it; settings.anthropicKey = it
            }
            KeyField("OpenAI", openai, showKeys) {
                openai = it; settings.openAiKey = it
            }
            KeyField("Mistral", mistral, showKeys) {
                mistral = it; settings.mistralKey = it
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = showKeys, onCheckedChange = { showKeys = it })
                Text("Show keys", style = MaterialTheme.typography.bodyMedium)
            }
        }

        // ---- transcription ----------------------------------------------
        SectionCard("Transcription") {
            Text(
                "Turns your voice into text.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ChoiceRow(
                options = TranscribeProvider.entries.map { it to it.label },
                selected = tProvider,
                onSelect = { tProvider = it; settings.transcribeProvider = it },
            )
            Text(
                "Anthropic is not listed: Claude accepts no audio input, so it " +
                    "cannot transcribe. It is available for polishing below.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = tModel,
                onValueChange = { tModel = it; settings.transcribeModel = it },
                label = { Text("Model override") },
                placeholder = { Text(Transcriber.defaultModelFor(tProvider)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // ---- polish -------------------------------------------------------
        SectionCard("Polish") {
            Text(
                "Applies spoken corrections (\"book pasta, sorry no, lasagna\"), " +
                    "removes filler, and punctuates.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ChoiceRow(
                options = PolishProvider.entries.map { it to it.label },
                selected = pProvider,
                onSelect = { pProvider = it; settings.polishProvider = it },
            )
            OutlinedTextField(
                value = pModel,
                onValueChange = { pModel = it; settings.polishModel = it },
                label = { Text("Model override") },
                placeholder = { Text(Polisher.defaultModelFor(pProvider)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Button(
                    enabled = !testing && pProvider != PolishProvider.NONE,
                    onClick = {
                        testing = true
                        testResult = null
                        scope.launch {
                            val r = ConnectionTest.test(settings, pProvider)
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
                    Text("Polish automatically", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Off: raw transcript is inserted; polish stays one tap away.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
