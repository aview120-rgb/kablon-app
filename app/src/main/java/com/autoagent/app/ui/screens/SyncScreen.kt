package com.autoagent.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.autoagent.app.access.AccessibilityHealth
import com.autoagent.app.data.RemoteState
import com.autoagent.app.sync.SyncEngine
import com.autoagent.app.ui.BigButton
import com.autoagent.app.ui.BigOutlined
import com.autoagent.app.ui.ButtonRow
import com.autoagent.app.ui.InfoCard
import com.autoagent.app.ui.SectionTitle
import com.autoagent.app.ui.StatusDot
import com.autoagent.app.ui.SyncViewModel
import com.autoagent.app.ui.theme.Bad
import com.autoagent.app.util.L
import com.autoagent.app.util.TimeText

@Composable
fun SyncScreen(vm: SyncViewModel) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsState()
    val server by vm.server.collectAsState()
    val health by vm.health.collectAsState()
    val queue by vm.queue.collectAsState()
    val tasks by vm.tasks.collectAsState()
    val outbox by vm.outboxCount.collectAsState()
    val logs by vm.logs.collectAsState()
    val msg by vm.message.collectAsState()
    val busy by vm.busy.collectAsState()

    var url by rememberSaveable { mutableStateOf(settings.serverUrl) }
    var token by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf(settings.deviceName) }
    val ltr = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr)

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { SectionTitle(L.t("Status", "מצב")) }
        item {
            InfoCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatusDot(server.connected)
                    Text(L.t("Server: ", "שרת: ") + server.label(), style = MaterialTheme.typography.titleMedium)
                }
                Text(L.t("Last sync: ", "סנכרון אחרון: ") + if (server.lastSyncAt > 0) TimeText.format(server.lastSyncAt) else L.t("never", "עדיין לא"))
                Text(L.t("Waiting to upload: $outbox", "ממתינים להעלאה: $outbox"))
                if (server.lastError.isNotBlank() && !server.connected) {
                    Text(L.t("Last error: ", "שגיאה אחרונה: ") + server.lastError + if (server.failures > 1) L.t(" (${server.failures} attempts)", " (${server.failures} ניסיונות)") else "", color = Bad)
                }
                Text(L.t("Device id: ", "מזהה מכשיר: ") + settings.deviceId, style = MaterialTheme.typography.bodyMedium)
            }
        }
        item {
            InfoCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatusDot(health.ready)
                    Text(L.t("Accessibility service: ", "שירות נגישות: ") + health.label(), style = MaterialTheme.typography.titleMedium)
                }
                if (health.connectedAt > 0) Text(L.t("Connected since ", "מחובר מאז ") + TimeText.format(health.connectedAt))
                if (health.lastError.isNotBlank()) Text(L.t("Last error: ", "שגיאה אחרונה: ") + health.lastError, color = Bad)
                Text(
                    queue.running?.let { L.t("Screen action now: $it", "פעולת מסך כעת: $it") } ?: L.t("Screen queue idle", "תור פעולות המסך פנוי") +
                        L.t(" · ${queue.completed} done", " · בוצעו ${queue.completed}"),
                )
                if (queue.waiting.isNotEmpty()) Text(L.t("Waiting: ", "ממתינות: ") + queue.waiting.joinToString(" · "))
                queue.waitReason?.let { Text(L.t("Paused: $it", "מושהה: $it")) }
                ButtonRow {
                    BigOutlined(L.t("Check / recover", "בדיקה ושחזור"), Modifier.weight(1f)) { vm.recoverAccessibility() }
                    BigOutlined(L.t("Open settings", "פתח הגדרות"), Modifier.weight(1f)) {
                        runCatching { context.startActivity(AccessibilityHealth.settingsIntent()) }
                    }
                }
            }
        }

        item { SectionTitle(L.t("Task-queue server", "שרת תור משימות")) }
        item {
            InfoCard {
                SwitchRow(L.t("Sync with server", "סנכרון עם השרת"), L.t("Tasks are processed on the server while the phone is offline and arrive when it reconnects.", "המשימות מעובדות בשרת כשהטלפון לא מקוון ומגיעות כשהוא מתחבר."), settings.syncEnabled, vm::setEnabled)
                OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), singleLine = true, textStyle = ltr,
                    label = { Text(L.t("Server address", "כתובת השרת")) }, placeholder = { Text("https://server:8787", style = ltr) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                OutlinedTextField(token, { token = it }, Modifier.fillMaxWidth(), singleLine = true, textStyle = ltr,
                    label = { Text(if (settings.serverTokenSet) L.t("Access token (saved; type to replace)", "אסימון גישה (שמור; הקלד כדי להחליף)") else L.t("Access token", "אסימון גישה")) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(L.t("Device name", "שם המכשיר")) })
                BigButton(L.t("Save", "שמירה")) { vm.save(url, token, name); token = "" }
                ButtonRow {
                    BigOutlined(L.t("Test connection", "בדיקת חיבור"), Modifier.weight(1f), enabled = !busy) { vm.testConnection() }
                    BigOutlined(L.t("Sync now", "סנכרן עכשיו"), Modifier.weight(1f), enabled = !busy && settings.syncEnabled) { vm.syncNow() }
                }
                msg?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                SwitchRow(L.t("Live connection", "חיבור חי"), L.t("While the app runs, tasks arrive within seconds; reconnects by itself.", "כשהאפליקציה פועלת, משימות מגיעות תוך שניות; מתחבר מחדש לבד."), settings.liveConnection, vm::setLive)
                SwitchRow(L.t("Upload notification texts", "העלאת טקסט התראות"), L.t("Lets the server build summaries while the phone is off. Off by default.", "מאפשר לשרת להכין סיכומים כשהטלפון כבוי. כבוי כברירת מחדל."), settings.uploadInbox, vm::setUploadInbox)
                SwitchRow(L.t("Server may press Send", "השרת רשאי ללחוץ שלח"), L.t("Off: server messages are only prepared in the app and you press Send.", "כבוי: הודעות מהשרת רק מוכנות באפליקציה ואתה לוחץ שלח."), settings.remoteAutoSend, vm::setRemoteAutoSend)
                BigOutlined(L.t("Ask the server for a daily summary at 08:00", "בקש מהשרת סיכום הודעות יומי ב-08:00"), Modifier.fillMaxWidth()) { vm.requestDailyDigest() }
            }
        }

        item { SectionTitle(L.t("Tasks from the server", "משימות מהשרת")) }
        if (tasks.isEmpty()) item { Text(L.t("No tasks yet.", "אין משימות עדיין."), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(tasks, key = { it.id }) { t ->
            InfoCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatusDot(t.state != RemoteState.FAILED && t.state != RemoteState.EXPIRED)
                    Text(t.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text(TimeText.format(t.executeAt))
                }
                Text(RemoteState.label(t.state) + if (t.note.isNotBlank()) " — ${t.note}" else "")
                if (t.state in RemoteState.FINAL && !t.reported) Text(L.t("Outcome waiting to reach the server", "התוצאה ממתינה להגיע לשרת"), style = MaterialTheme.typography.bodyMedium)
            }
        }

        item { SectionTitle(L.t("Action log", "יומן פעולות")) }
        items(logs.take(80), key = { it.id }) { l ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                StatusDot(l.ok)
                Column(Modifier.weight(1f)) {
                    Text(l.message, style = MaterialTheme.typography.bodyMedium)
                    Text(TimeText.format(l.at) + " · " + kindLabel(l.kind), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item { Text(" ") }
    }
}

private fun kindLabel(kind: String) = when (kind) {
    "sync" -> L.t("sync", "סנכרון")
    "send" -> L.t("send", "שליחה")
    "sms" -> "SMS"
    "schedule" -> L.t("schedule", "תזמון")
    "access" -> L.t("accessibility", "נגישות")
    "service" -> L.t("service", "שירות")
    "alarm" -> L.t("alarm", "שעון מעורר")
    "call" -> L.t("call", "שיחה")
    else -> kind
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
