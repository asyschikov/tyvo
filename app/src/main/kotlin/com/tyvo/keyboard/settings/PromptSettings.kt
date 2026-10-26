package com.tyvo.keyboard.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tyvo.keyboard.actions.QuickAction
import com.tyvo.keyboard.actions.QuickActions
import com.tyvo.keyboard.data.Settings
import com.tyvo.keyboard.polish.Polisher
import com.tyvo.keyboard.usage.UsageStore
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Runs a sample through a prompt before it is saved, with the user's own
 * provider and model. Without it, the only feedback on an edited prompt is
 * a mangled message some time later.
 */
@Composable
internal fun TryBox(
    settings: Settings,
    run: suspend Polisher.(String) -> String,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var sample by remember { mutableStateOf("") }
    var output by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = sample,
            onValueChange = { sample = it },
            label = { Text("Try it") },
            placeholder = { Text("Type something the way you would say it") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                enabled = !busy && sample.isNotBlank(),
                onClick = {
                    busy = true
                    output = null
                    error = null
                    scope.launch {
                        runCatching { Polisher(settings, UsageStore(ctx)).run(sample) }
                            .onSuccess { output = it }
                            .onFailure { error = it.message ?: "Failed." }
                        busy = false
                    }
                },
            ) { Text(if (busy) "Running…" else "Run") }
        }
        output?.let {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
            ) {
                Text(it, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }
        error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

/**
 * The whole clean-up prompt, editable. Opens on exactly what is sent today,
 * so an edit starts from the working prompt rather than a blank box.
 * [onChanged] says whether the user's own prompt is now in use.
 */
@Composable
internal fun CleanupPromptEditor(settings: Settings, onChanged: (overridden: Boolean) -> Unit) {
    var saved by remember { mutableStateOf(settings.cleanupPrompt) }
    var text by remember { mutableStateOf(settings.cleanupSystemPrompt()) }
    var defaultChanged by remember { mutableStateOf(settings.cleanupDefaultChanged) }
    var showingDefault by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    val built = remember { settings.builtCleanupPrompt() }
    val modified = saved.isNotBlank()

    fun reset() {
        settings.resetCleanupPrompt()
        saved = ""
        text = built
        defaultChanged = false
        onChanged(false)
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "The full instruction sent with every dictation. While you use your " +
                    "own, the corrections and your rules in the Clean-up tab are not applied.",
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (modified) {
                Spacer(Modifier.width(8.dp))
                ModifiedBadge("Modified")
            }
        }

        if (defaultChanged) {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                ),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "The built-in prompt has changed since you edited yours.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row {
                        TextButton(onClick = { showingDefault = !showingDefault }) {
                            Text(if (showingDefault) "Hide new version" else "Show new version")
                        }
                        TextButton(onClick = { reset() }) { Text("Use new version") }
                    }
                    if (showingDefault) {
                        Text(
                            built,
                            style = MaterialTheme.typography.bodySmall
                                .copy(fontFamily = FontFamily.Monospace),
                        )
                    }
                }
            }
        }

        // Collapsed until asked for: the full prompt is several screens long
        // and would push everything below it out of reach.
        if (!expanded) {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(onClick = { expanded = true }) { Text("Show and edit") }
        } else {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxWidth(),
                minLines = 12,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = text != settings.cleanupSystemPrompt(),
                    onClick = {
                        settings.saveCleanupPrompt(text)
                        saved = settings.cleanupPrompt
                        defaultChanged = settings.cleanupDefaultChanged
                        onChanged(saved.isNotBlank())
                    },
                ) { Text("Save") }
                OutlinedButton(
                    enabled = modified || text != built,
                    onClick = { reset() },
                ) { Text("Reset to default") }
            }
            TextButton(onClick = { expanded = false }) { Text("Collapse") }
        }

        HorizontalDivider()
        Text(
            "Runs the prompt as it is in the box above, saved or not.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TryBox(settings) { cleanUp(it, system = text) }
    }
}

/** The action bar's actions: tap one to change what it does, or add your own. */
@Composable
internal fun QuickActionsEditor(settings: Settings) {
    var actions by remember { mutableStateOf(settings.quickActions()) }
    var edits by remember { mutableStateOf(settings.actionEdits) }
    var removed by remember { mutableStateOf(settings.removedActions) }
    // Null: closed. A blank id: a new action being made.
    var editing by remember { mutableStateOf<QuickAction?>(null) }

    fun refresh() {
        actions = settings.quickActions()
        edits = settings.actionEdits
        removed = settings.removedActions
    }

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            "Tap one to change what it does.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        actions.forEach { a ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { editing = a }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(a.label, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        a.instruction,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                when {
                    a.group == QuickAction.Group.CUSTOM -> ModifiedBadge("Yours")
                    a.id in edits -> ModifiedBadge("Edited")
                }
                if (a.group == QuickAction.Group.CUSTOM) {
                    TextButton(onClick = {
                        settings.customActions = settings.customActions.filter { it.id != a.id }
                        refresh()
                    }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                } else {
                    TextButton(onClick = {
                        settings.removedActions = settings.removedActions + a.id
                        refresh()
                    }) { Text("Hide") }
                }
            }
        }
        TextButton(onClick = { editing = QuickActions.custom("", "", "") }) { Text("+ New action") }

        // Hidden built-ins stay listed, so taking one off the bar is never
        // a decision that cannot be undone from here.
        val gone = QuickActions.ALL.filter { it.id in removed }
        if (gone.isNotEmpty()) {
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Text("Hidden", style = MaterialTheme.typography.labelLarge)
            gone.forEach { a ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        a.label,
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = {
                        settings.removedActions = settings.removedActions - a.id
                        refresh()
                    }) { Text("Show") }
                }
            }
        }
    }

    editing?.let { a ->
        ActionEditorDialog(
            settings = settings,
            action = a,
            edited = a.id in edits,
            onDismiss = { editing = null },
            onChanged = { refresh(); editing = null },
        )
    }
}

@Composable
private fun ActionEditorDialog(
    settings: Settings,
    action: QuickAction,
    edited: Boolean,
    onDismiss: () -> Unit,
    onChanged: () -> Unit,
) {
    val isNew = action.id.isBlank()
    val isCustom = action.group == QuickAction.Group.CUSTOM
    val builtIn = QuickActions.byId(action.id)
    var label by remember { mutableStateOf(action.label) }
    var instruction by remember { mutableStateOf(action.instruction) }

    fun save() {
        if (isCustom) {
            val id = action.id.ifBlank { QuickActions.CUSTOM_PREFIX + UUID.randomUUID() }
            val made = QuickActions.custom(id, label, instruction)
            val others = settings.customActions
            settings.customActions = if (others.any { it.id == id }) {
                others.map { if (it.id == id) made else it }
            } else {
                others + made
            }
        } else if (builtIn != null) {
            // Back to the built-in wording counts as no edit, so a later
            // improvement to it still reaches this user.
            settings.actionEdits = if (instruction.trim() == builtIn.instruction) {
                settings.actionEdits - action.id
            } else {
                settings.actionEdits + (action.id to instruction.trim())
            }
        }
        onChanged()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "New action" else action.label) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (isCustom) {
                    OutlinedTextField(
                        value = label,
                        onValueChange = { label = it.take(MAX_LABEL) },
                        label = { Text("Button label") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                OutlinedTextField(
                    value = instruction,
                    onValueChange = { instruction = it },
                    label = { Text("Instruction") },
                    placeholder = { Text("What the button should do to your text") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                )
                if (!isCustom && builtIn != null && (edited || instruction != builtIn.instruction)) {
                    TextButton(onClick = { instruction = builtIn.instruction }) {
                        Text("Restore built-in wording")
                    }
                }
                TryBox(settings) {
                    transform(it, instruction, translating = action.translating)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = instruction.isNotBlank() && (!isCustom || label.isNotBlank()),
                onClick = { save() },
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (isCustom && !isNew) {
                    TextButton(onClick = {
                        settings.customActions = settings.customActions.filter { it.id != action.id }
                        onChanged()
                    }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                }
                // A built-in is only hidden: it can be shown again from the list.
                if (!isCustom && builtIn != null) {
                    TextButton(onClick = {
                        settings.removedActions = settings.removedActions + action.id
                        onChanged()
                    }) { Text("Hide") }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
internal fun ModifiedBadge(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text,
            Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/** Chips in the action bar stay readable at this length. */
private const val MAX_LABEL = 14
