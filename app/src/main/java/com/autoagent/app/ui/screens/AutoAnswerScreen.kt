package com.autoagent.app.ui.screens

import com.autoagent.app.util.L
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
                        Text(L.t("Auto-answer unknown numbers", "מענה אוטומטי למספרים לא מוכרים"), style = MaterialTheme.typography.titleMedium)
                        Text(L.t("Only callers NOT in your contacts. Hidden numbers are never answered.", "רק למתקשרים שלא באנשי הקשר. מספרים חסויים אף פעם לא נענים."), style = MaterialTheme.typography.bodyMedium)
                    }
                    Switch(checked = s.autoAnswerEnabled, onCheckedChange = vm::setEnabled)
                }
                Text(L.t("Ring for ${s.autoAnswerDelaySec}s first (you can still pick up)", "קודם לצלצל ${s.autoAnswerDelaySec} שניות (אפשר עדיין לענות בעצמך)"))
                Slider(value = s.autoAnswerDelaySec.toFloat(), onValueChange = { vm.setDelay(it.toInt()) }, valueRange = 0f..30f, steps = 29)
                Text(L.t("Caller turns before closing: ${s.maxTurns}", "מספר תורות של המתקשר לפני סיום: ${s.maxTurns}"))
                Slider(value = s.maxTurns.toFloat(), onValueChange = { vm.setTurns(it.toInt()) }, valueRange = 1f..6f, steps = 4)
                msg?.let { Text(it, color = Warn, modifier = Modifier.clickable { vm.message.value = null }) }
            }
        }
        item {
            InfoCard {
                Text(L.t("Script", "תסריט"), style = MaterialTheme.typography.titleMedium)
                Text("1. \"${CallBrain.GREETING}\"  " + L.t("(always first)", "(תמיד ראשון)"))
                Text("2. \"${CallBrain.ASK}\"")
                Text(L.t("3. Reply from the keyword rules below; unknown → ", "3. תשובה לפי כללי מילות המפתח למטה; לא מזוהה ← ") + "\"${CallBrain.UNKNOWN_REPLY}\"")
                Text("4. \"${CallBrain.CLOSING}\"")
                Text(
                    L.t(
                        "Android does not let regular apps play audio into the call or capture call audio. AutoAgent switches to " +
                            "speakerphone and speaks beside the microphone. If the OS blocks call audio, only the transcript is saved and the reason is shown.",
                        "אנדרואיד לא מאפשר לאפליקציות רגילות להשמיע קול לתוך השיחה או להקליט אותה. AutoAgent עובר לרמקול " +
                            "ומדבר ליד המיקרופון. אם המערכת חוסמת את קול השיחה, נשמר רק התמלול ומוצגת הסיבה.",
                    ),
                    style = MaterialTheme.typography.bodyMedium, color = Warn,
                )
                BigOutlined(L.t("Run a test call (self-managed line)", "הפעלת שיחת בדיקה (קו בדיקה פנימי)"), Modifier.fillMaxWidth()) { vm.testCall() }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle(L.t("Keyword rules", "כללי מילות מפתח"))
                Text("", Modifier.weight(1f))
                TextButton(onClick = { editing = KeywordRule(intent = RuleIntent.CUSTOM, keywords = "", response = "") }) { Text(L.t("Add rule", "הוספת כלל")) }
            }
        }
        items(rules, key = { "r${it.id}" }) { r ->
            InfoCard(Modifier.clickable { editing = r }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(r.intent, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Switch(checked = r.enabled, onCheckedChange = { vm.saveRule(r.copy(enabled = it)) })
                }
                Text(L.t("If caller says: ${r.keywords}", "אם המתקשר אומר: ${r.keywords}"), style = MaterialTheme.typography.bodyMedium)
                Text(L.t("Reply: ", "תשובה: ") + if (L.hebrew && r.responseHe.isNotBlank()) r.responseHe else r.response, style = MaterialTheme.typography.bodyMedium)
            }
        }
        item { SectionTitle(L.t("Answered calls", "שיחות שנענו")) }
        if (records.isEmpty()) item { Text(L.t("No assistant calls yet.", "עדיין אין שיחות שהעוזר ענה להן.")) }
        items(records, key = { "c${it.id}" }) { r -> RecordCard(r, playing == r.id, vm) }
        item { Text(" ") }
    }

    editing?.let { rule -> RuleEditor(rule, onDismiss = { editing = null }, onSave = { vm.saveRule(it); editing = null }, onDelete = { vm.deleteRule(it); editing = null }) }
}

@Composable
private fun RecordCard(r: CallRecord, isPlaying: Boolean, vm: AutoAnswerViewModel) {
    InfoCard {
        Row {
            Text(if (r.isTest) L.t("Test call", "שיחת בדיקה") else r.number, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(TimeText.format(r.startedAt))
        }
        if (r.intents.isNotBlank()) Text(L.t("Intents: ${r.intents}", "כוונות: ${r.intents}"), style = MaterialTheme.typography.bodyMedium)
        Text(r.transcript.ifBlank { L.t("(no transcript)", "(אין תמלול)") }, style = MaterialTheme.typography.bodyMedium)
        Text(r.audioNote, style = MaterialTheme.typography.bodyMedium, color = if (r.audioPath != null) Ok else Warn)
        ButtonRow {
            if (!r.isTest) BigButton(L.t("Call back", "לחזור"), Modifier.weight(1f)) { vm.callBack(r) }
            if (r.audioPath != null && File(r.audioPath).exists()) BigOutlined(if (isPlaying) L.t("Stop", "עצירה") else L.t("Play", "השמעה"), Modifier.weight(1f)) { vm.togglePlay(r) }
        }
        ButtonRow {
            if (!r.callbackDone && !r.isTest) BigOutlined(L.t("Mark done", "סימון כטופל"), Modifier.weight(1f)) { vm.markDone(r) }
            BigOutlined(L.t("Delete", "מחיקה"), Modifier.weight(1f)) { vm.deleteRecord(r) }
        }
    }
}

@Composable
private fun RuleEditor(rule: KeywordRule, onDismiss: () -> Unit, onSave: (KeywordRule) -> Unit, onDelete: (KeywordRule) -> Unit) {
    var intent by remember { mutableStateOf(rule.intent) }
    var keywords by remember { mutableStateOf(rule.keywords) }
    var response by remember { mutableStateOf(rule.response) }
    var responseHe by remember { mutableStateOf(rule.responseHe) }
    var priority by remember { mutableStateOf(rule.priority.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (rule.id == 0L) L.t("New rule", "כלל חדש") else L.t("Edit rule", "עריכת כלל")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(intent, { intent = it.uppercase().replace(' ', '_') }, label = { Text(L.t("Intent name", "שם הכוונה")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(keywords, { keywords = it }, label = { Text(L.t("Keywords, comma separated", "מילות מפתח, מופרדות בפסיק")) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(responseHe, { responseHe = it }, label = { Text(L.t("Spoken reply (Hebrew)", "תשובה מוקראת (עברית)")) }, minLines = 2, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(response, { response = it }, label = { Text(L.t("Spoken reply (English)", "תשובה מוקראת (אנגלית)")) }, minLines = 2, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(priority, { priority = it.filter(Char::isDigit).take(3) }, label = { Text(L.t("Priority (higher first)", "עדיפות (גבוה קודם)")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(
                enabled = keywords.isNotBlank() && (response.isNotBlank() || responseHe.isNotBlank()) && intent.isNotBlank(),
                onClick = {
                    onSave(
                        rule.copy(
                            intent = intent, keywords = keywords,
                            response = response.ifBlank { responseHe }, responseHe = responseHe,
                            priority = priority.toIntOrNull() ?: 0,
                        )
                    )
                },
            ) { Text(L.t("Save", "שמירה")) }
        },
        dismissButton = {
            Row {
                if (rule.id != 0L) TextButton(onClick = { onDelete(rule) }) { Text(L.t("Delete", "מחיקה")) }
                TextButton(onClick = onDismiss) { Text(L.t("Cancel", "ביטול")) }
            }
        },
    )
}
