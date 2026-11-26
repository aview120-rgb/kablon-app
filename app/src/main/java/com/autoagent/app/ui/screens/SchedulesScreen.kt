package com.autoagent.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.autoagent.app.data.ScheduledSend
import com.autoagent.app.data.SendChannel
import com.autoagent.app.data.SendStatus
import com.autoagent.app.schedule.SendScheduler
import com.autoagent.app.ui.BigButton
import com.autoagent.app.ui.BigOutlined
import com.autoagent.app.ui.ButtonRow
import com.autoagent.app.ui.InfoCard
import com.autoagent.app.ui.SchedulesViewModel
import com.autoagent.app.ui.TargetApp
import com.autoagent.app.ui.theme.Bad
import com.autoagent.app.ui.theme.Ok
import com.autoagent.app.ui.theme.Warn
import com.autoagent.app.util.Contacts
import com.autoagent.app.util.TimeText

@Composable
fun SchedulesScreen(vm: SchedulesViewModel) {
    var tab by remember { mutableIntStateOf(0) }
    val msg by vm.message.collectAsState()
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Messages") }, modifier = Modifier.heightIn(min = 56.dp))
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Alarms") }, modifier = Modifier.heightIn(min = 56.dp))
        }
        msg?.let {
            Text(it, modifier = Modifier.padding(16.dp).clickable { vm.message.value = null }, color = Warn)
        }
        if (tab == 0) MessagesTab(vm) else AlarmsTab(vm)
    }
}

@Composable
private fun MessagesTab(vm: SchedulesViewModel) {
    val sends by vm.sends.collectAsState()
    var editing by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { BigButton("New scheduled message", Modifier.padding(top = 12.dp)) { editing = true } }
        item {
            Text(
                "One recipient per message, max ${SendScheduler.DAILY_CAP} per day. SMS goes through Android's SmsManager. " +
                    "Other apps are driven by the accessibility service: it opens the app, pastes only your saved text and taps Send. " +
                    "If the screen does not match, it aborts and notifies you.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(sends, key = { it.id }) { s ->
            InfoCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${s.appLabel} → ${s.recipientLabel}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text(
                        s.status,
                        color = when (s.status) { SendStatus.SENT -> Ok; SendStatus.PENDING, SendStatus.RUNNING -> Warn; else -> Bad },
                    )
                }
                Text(TimeText.full(s.sendAt))
                Text("\"${s.text}\"", style = MaterialTheme.typography.bodyMedium)
                if (s.resultNote.isNotBlank()) Text(s.resultNote, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ButtonRow {
                    if (s.status == SendStatus.PENDING) BigOutlined("Cancel", Modifier.weight(1f)) { vm.cancel(s) }
                    BigOutlined("Delete", Modifier.weight(1f)) { vm.delete(s) }
                }
            }
        }
        item { Text(" ") }
    }
    if (editing) SendEditor(vm, onDismiss = { editing = false })
}

@Composable
private fun SendEditor(vm: SchedulesViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val targets = remember { vm.shareTargets() }
    val whatsapp = targets.firstOrNull { it.packageName == "com.whatsapp" } ?: targets.firstOrNull { it.packageName == "com.whatsapp.w4b" }
    var channel by remember { mutableStateOf(SendChannel.SMS) }
    var app by remember { mutableStateOf<TargetApp?>(null) }
    var appMenu by remember { mutableStateOf(false) }
    var recipient by remember { mutableStateOf("") }
    var recipientLabel by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var at by remember { mutableLongStateOf(System.currentTimeMillis() + 3_600_000L) }
    var pickError by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickContact()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val res = Contacts.numbersForContactUri(context, uri)
        if (res == null || res.second.isEmpty()) {
            pickError = if (res == null) "Contacts permission is needed to read the number" else "That contact has no phone number"
        } else {
            recipient = res.second.first()
            recipientLabel = res.first
            pickError = if (res.second.size > 1) "Using ${res.second.first()} (contact has ${res.second.size} numbers; edit if needed)" else null
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Scheduled message") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(channel == SendChannel.SMS, { channel = SendChannel.SMS }, { Text("SMS") })
                    if (whatsapp != null) FilterChip(channel == SendChannel.WHATSAPP, { channel = SendChannel.WHATSAPP; app = whatsapp }, { Text("WhatsApp") })
                    FilterChip(channel == SendChannel.APP, { channel = SendChannel.APP; appMenu = true }, { Text("Other app") })
                }
                if (channel == SendChannel.APP) {
                    Column {
                        OutlinedButton(onClick = { appMenu = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                            Text(app?.label ?: "Choose app")
                        }
                        DropdownMenu(expanded = appMenu, onDismissRequest = { appMenu = false }) {
                            targets.forEach { t -> DropdownMenuItem(text = { Text(t.label) }, onClick = { app = t; appMenu = false }) }
                        }
                    }
                }
                when (channel) {
                    SendChannel.SMS -> {
                        OutlinedTextField(recipient, { recipient = it; recipientLabel = it }, label = { Text("Phone number") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        BigOutlined("Pick contact", Modifier.fillMaxWidth()) { picker.launch(null) }
                    }
                    SendChannel.WHATSAPP -> {
                        Text("Phone in international format (+972…), or the exact contact/group name as shown in WhatsApp.", style = MaterialTheme.typography.bodyMedium)
                        OutlinedTextField(recipient, { recipient = it; recipientLabel = it }, label = { Text("+number or exact name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        BigOutlined("Pick contact", Modifier.fillMaxWidth()) { picker.launch(null) }
                    }
                    else -> {
                        Text("Exact contact or group name as the app shows it in its share list.", style = MaterialTheme.typography.bodyMedium)
                        OutlinedTextField(recipientLabel, { recipientLabel = it; recipient = it }, label = { Text("Exact name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                }
                if (recipientLabel.isNotBlank() && recipientLabel != recipient) Text("To: $recipientLabel ($recipient)")
                pickError?.let { Text(it, color = Warn) }
                OutlinedTextField(text, { text = it.take(SendScheduler.MAX_TEXT) }, label = { Text("Message text") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                BigOutlined("When: ${TimeText.full(at)}", Modifier.fillMaxWidth()) { pickDateTime(context, at) { at = it } }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val target = if (channel == SendChannel.SMS) TargetApp("sms", "SMS") else app
                if (target == null) { pickError = "Choose an app"; return@TextButton }
                val r = recipient.trim()
                val pickedFromContacts = recipientLabel.isNotBlank() && recipientLabel != recipient
                val finalRecipient = when {
                    channel != SendChannel.WHATSAPP -> r
                    r.startsWith("+") -> r
                    pickedFromContacts -> recipientLabel.trim()
                    r.any { it.isLetter() } -> r
                    else -> { pickError = "Use international format starting with + for WhatsApp numbers"; return@TextButton }
                }
                vm.schedule(
                    ScheduledSend(
                        channel = channel,
                        packageName = target.packageName,
                        appLabel = target.label,
                        recipient = finalRecipient,
                        recipientLabel = recipientLabel.trim().ifBlank { finalRecipient },
                        text = text.trim(),
                        sendAt = at,
                    ),
                    onDone = onDismiss,
                )
            }) { Text("Schedule") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun AlarmsTab(vm: SchedulesViewModel) {
    val context = LocalContext.current
    val alarms by vm.alarms.collectAsState()
    var label by remember { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            InfoCard(Modifier.padding(top = 12.dp)) {
                OutlinedTextField(label, { label = it }, label = { Text("Label (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                BigButton("Pick time and set alarm") {
                    pickDateTime(context, System.currentTimeMillis() + 3_600_000L) { vm.addAlarm(it, label); label = "" }
                }
                Text("Exact alarms survive reboot and Doze.", style = MaterialTheme.typography.bodyMedium)
            }
        }
        items(alarms, key = { it.id }) { a ->
            InfoCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(TimeText.full(a.triggerAt), style = MaterialTheme.typography.titleMedium)
                        Text(a.label)
                    }
                    Switch(checked = a.enabled, onCheckedChange = { vm.toggleAlarm(a, it) })
                }
                BigOutlined("Delete", Modifier.fillMaxWidth()) { vm.deleteAlarm(a) }
            }
        }
        item { Text(" ") }
    }
}
