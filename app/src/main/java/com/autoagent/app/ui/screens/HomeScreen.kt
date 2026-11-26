package com.autoagent.app.ui.screens

import com.autoagent.app.util.L
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.autoagent.app.ui.HomeViewModel
import com.autoagent.app.ui.InfoCard
import com.autoagent.app.ui.Permissions
import com.autoagent.app.ui.SectionTitle
import com.autoagent.app.ui.theme.Bad
import com.autoagent.app.util.TimeText

@Composable
fun HomeScreen(vm: HomeViewModel, go: (String) -> Unit) {
    val context = LocalContext.current
    val actions by vm.actions.collectAsState()
    val inbox by vm.inboxCount.collectAsState()
    val dial by vm.dialText.collectAsState()
    val msg by vm.message.collectAsState()
    var missing by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        missing = Permissions.items(context).count { !it.granted }
        onPauseOrDispose { }
    }
    LaunchedEffect(Unit) { vm.refreshService() }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (missing > 0) item {
            InfoCard(Modifier.clickable { go("permissions") }, container = Bad.copy(alpha = 0.25f)) {
                Text(L.t("$missing permission(s) missing", "חסרות $missing הרשאות"), style = MaterialTheme.typography.titleMedium)
                Text(L.t("Tap to open the checklist.", "הקש כדי לפתוח את רשימת ההרשאות."))
            }
        }
        item {
            InfoCard {
                Text(L.t("Call a number", "חיוג למספר"), style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = dial, onValueChange = { vm.dialText.value = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text(L.t("Phone number", "מספר טלפון")) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                )
                Button(onClick = { vm.call(dial) }, modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp)) { Text(L.t("Call", "חייג")) }
                msg?.let { Text(it, color = Bad) }
            }
        }
        item { SectionTitle(L.t("Next actions", "הפעולות הבאות")) }
        items(actions) { a ->
            Card(
                Modifier.fillMaxWidth().clickable { go(a.route) },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row { Text(a.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f)); a.at?.let { Text(TimeText.format(it)) } }
                    if (a.detail.isNotBlank()) Text(a.detail, style = MaterialTheme.typography.bodyMedium)
                    a.callNumber?.let { n -> Button(onClick = { vm.call(n) }, modifier = Modifier.heightIn(min = 52.dp)) { Text(L.t("Call back", "לחזור")) } }
                }
            }
        }
        item { SectionTitle(L.t("Go to", "מעבר אל")) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Tile(L.t("Schedules", "תזמונים"), L.t("Messages and alarms", "הודעות ושעונים מעוררים")) { go("schedules") }
                Tile(L.t("Auto-answer rules", "כללי מענה אוטומטי"), L.t("Unknown callers, templates, transcripts", "מתקשרים לא מוכרים, תבניות, תמלולים")) { go("autoanswer") }
                Tile(L.t("Inbox summaries", "סיכומי הודעות"), L.t("$inbox message(s) in the last 24 h", "$inbox הודעות ב-24 השעות האחרונות")) { go("inbox") }
                Tile(L.t("Receipts", "קבלות"), L.t("Share an image or voice note to AutoAgent", "שתף תמונה או הקלטה ל-AutoAgent")) { go("receipts") }
                Tile(L.t("Voice", "פקודות קוליות"), L.t("Tap the mic and speak a command", "הקש על המיקרופון ותגיד פקודה")) { go("voice") }
                Tile(L.t("Permissions", "הרשאות"), L.t("Green = ready, red = needs action", "ירוק = מוכן, אדום = צריך טיפול")) { go("permissions") }
            }
        }
        item { Text(" ") }
    }
}

@Composable
private fun Tile(title: String, subtitle: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
