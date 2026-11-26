package com.autoagent.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.autoagent.app.calls.CallBrain
import com.autoagent.app.data.CallRecord
import com.autoagent.app.data.KeywordRule
import com.autoagent.app.data.RuleIntent
import com.autoagent.app.ui.AutoAnswerViewModel
import com.autoagent.app.ui.BigButton
import com.autoagent.app.ui.BigOutlined
import com.autoagent.app.ui.ButtonRow
import com.autoagent.app.ui.InfoCard
import com.autoagent.app.ui.SectionTitle
import com.autoagent.app.ui.theme.Ok
import com.autoagent.app.ui.theme.Warn
import com.autoagent.app.util.TimeText
import java.io.File

@Composable
fun AutoAnswerScreen(vm: AutoAnswerViewModel) {
    val s by vm.settings.collectAsState()
    val rules by vm.rules.collectAsState()
    val records by vm.records.collectAsState()
    val msg by vm.message.collectAsState()
    val playing by vm.playing.collectAsState()
    var editing by remember { mutableStateOf<KeywordRule?>(null) }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            InfoCard(Modifier.padding(top = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Auto-answer unknown numbers", style = MaterialTheme.typography.titleMedium)
                        Text("Only callers NOT in your contacts. Hidden numbers are never answered.", style = MaterialTheme.typography.bodyMedium)
                    }
                    Switch(checked = s.autoAnswerEnabled, onCheckedChange = vm::setEnabled)
                }
                Text("Ring for ${s.autoAnswerDelaySec}s first (you can still pick up)")
                Slider(value = s.autoAnswerDelaySec.toFloat(), onValueChange = { vm.setDelay(it.toInt()) }, valueRange = 0f..30f, steps = 29)
                Text("Caller turns before closing: ${s.maxTurns}")
                Slider(value = s.maxTurns.toFloat(), onValueChange = { vm.setTurns(it.toInt()) }, valueRange = 1f..6f, steps = 4)
                msg?.let { Text(it, color = Warn, modifier = Modifier.clickable { vm.message.value = null }) }
            }
        }
        item {
            InfoCard {
                Text("Script", style = MaterialTheme.typography.titleMedium)
                Text("1. \"${CallBrain.GREETING}\"  (always first)")
                Text("2. \"${CallBrain.ASK}\"")
                Text("3. Reply from the keyword rules below; unknown → \"${CallBrain.UNKNOWN_REPLY}\"")
                Text("4. \"${CallBrain.CLOSING}\"")
                Text(
                    "Android does not let regular apps play audio into the call or capture call audio. AutoAgent switches to " +
                        "speakerphone and speaks beside the microphone. If the OS blocks call audio, only the transcript is saved and the reason is shown.",
                    style = MaterialTheme.typography.bodyMedium, color = Warn,
                )
                BigOutlined("Run a test call (self-managed line)", Modifier.fillMaxWidth()) { vm.testCall() }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("Keyword rules")
                Text("", Modifier.weight(1f))
                TextButton(onClick = { editing = KeywordRule(intent = RuleIntent.CUSTOM, keywords = "", response = "") }) { Text("Add rule") }
            }
        }
        items(rules, key = { "r${it.id}" }) { r ->
            InfoCard(Modifier.clickable { editing = r }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(r.intent, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Switch(checked = r.enabled, onCheckedChange = { vm.saveRule(r.copy(enabled = it)) })
                }
                Text("If caller says: ${r.keywords}", style = MaterialTheme.typography.bodyMedium)
                Text("Reply: ${r.response}", style = MaterialTheme.typography.bodyMedium)
            }
        }
        item { SectionTitle("Answered calls") }
        if (records.isEmpty()) item { Text("No assistant calls yet.") }
        items(records, key = { "c${it.id}" }) { r -> RecordCard(r, playing == r.id, vm) }
        item { Text(" ") }
    }

    editing?.let { rule -> RuleEditor(rule, onDismiss = { editing = null }, onSave = { vm.saveRule(it); editing = null }, onDelete = { vm.deleteRule(it); editing = null }) }
}

@Composable
private fun RecordCard(r: CallRecord, isPlaying: Boolean, vm: AutoAnswerViewModel) {
    InfoCard {
        Row {
            Text(if (r.isTest) "Test call" else r.number, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(TimeText.format(r.startedAt))
        }
        if (r.intents.isNotBlank()) Text("Intents: ${r.intents}", style = MaterialTheme.typography.bodyMedium)
        Text(r.transcript.ifBlank { "(no transcript)" }, style = MaterialTheme.typography.bodyMedium)
        Text(r.audioNote, style = MaterialTheme.typography.bodyMedium, color = if (r.audioPath != null) Ok else Warn)
        ButtonRow {
            if (!r.isTest) BigButton("Call back", Modifier.weight(1f)) { vm.callBack(r) }
            if (r.audioPath != null && File(r.audioPath).exists()) BigOutlined(if (isPlaying) "Stop" else "Play", Modifier.weight(1f)) { vm.togglePlay(r) }
        }
        ButtonRow {
            if (!r.callbackDone && !r.isTest) BigOutlined("Mark done", Modifier.weight(1f)) { vm.markDone(r) }
            BigOutlined("Delete", Modifier.weight(1f)) { vm.deleteRecord(r) }
        }
    }
}

@Composable
private fun RuleEditor(rule: KeywordRule, onDismiss: () -> Unit, onSave: (KeywordRule) -> Unit, onDelete: (KeywordRule) -> Unit) {
    var intent by remember { mutableStateOf(rule.intent) }
    var keywords by remember { mutableStateOf(rule.keywords) }
    var response by remember { mutableStateOf(rule.response) }
    var priority by remember { mutableStateOf(rule.priority.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (rule.id == 0L) "New rule" else "Edit rule") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(intent, { intent = it.uppercase().replace(' ', '_') }, label = { Text("Intent name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(keywords, { keywords = it }, label = { Text("Keywords, comma separated") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(response, { response = it }, label = { Text("Spoken reply") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(priority, { priority = it.filter(Char::isDigit).take(3) }, label = { Text("Priority (higher first)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(
                enabled = keywords.isNotBlank() && response.isNotBlank() && intent.isNotBlank(),
                onClick = { onSave(rule.copy(intent = intent, keywords = keywords, response = response, priority = priority.toIntOrNull() ?: 0)) },
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (rule.id != 0L) TextButton(onClick = { onDelete(rule) }) { Text("Delete") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
