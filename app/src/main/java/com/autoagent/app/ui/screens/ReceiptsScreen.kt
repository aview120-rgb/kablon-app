package com.autoagent.app.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.autoagent.app.data.Receipt
import com.autoagent.app.receipts.ReceiptIngest
import com.autoagent.app.receipts.ReceiptParser
import com.autoagent.app.ui.BigButton
import com.autoagent.app.ui.BigOutlined
import com.autoagent.app.ui.ButtonRow
import com.autoagent.app.ui.InfoCard
import com.autoagent.app.ui.ReceiptsViewModel
import com.autoagent.app.ui.SectionTitle
import com.autoagent.app.ui.theme.Warn
import com.autoagent.app.util.TimeText
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun ReceiptsScreen(vm: ReceiptsViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val receipts by vm.receipts.collectAsState()
    val busy by vm.busy.collectAsState()
    val msg by vm.message.collectAsState()
    val partial by vm.partial.collectAsState()
    val settings by vm.settings.collectAsState()
    var editing by remember { mutableStateOf<Receipt?>(null) }
    var imapOpen by remember { mutableStateOf(false) }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val f = runCatching { ReceiptIngest.copyToStorage(context, uri, context.contentResolver.getType(uri)) }.getOrNull()
            if (f != null) vm.ingest(f.absolutePath, false) else vm.message.value = "Could not read that image"
        }
    }
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            val f = runCatching { ReceiptIngest.copyToStorage(context, uri, context.contentResolver.getType(uri)) }.getOrNull()
            if (f != null) vm.ingest(f.absolutePath, true) else vm.message.value = "Could not read that audio file"
        }
    }
    val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) vm.dictate() else vm.message.value = "Microphone permission is needed to dictate"
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            InfoCard(Modifier.padding(top = 12.dp)) {
                Text("Share a receipt photo or voice note to AutoAgent from any app, or add one here. OCR and speech-to-text run on this phone.", style = MaterialTheme.typography.bodyMedium)
                ButtonRow {
                    BigButton("Photo", Modifier.weight(1f), enabled = !busy) { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                    BigButton("Voice note", Modifier.weight(1f), enabled = !busy) { pickAudio.launch("audio/*") }
                }
                ButtonRow {
                    BigOutlined("Dictate receipt", Modifier.weight(1f), enabled = !busy) { micPerm.launch(Manifest.permission.RECORD_AUDIO) }
                    BigOutlined("Type it", Modifier.weight(1f)) { vm.addManual() }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (partial.isNotBlank()) Text("\"$partial\"")
                msg?.let { Text(it, color = Warn, modifier = Modifier.clickable { vm.message.value = null }) }
            }
        }
        item {
            InfoCard {
                Text("E-mail receipts (optional)", style = MaterialTheme.typography.titleMedium)
                if (settings.imapPasswordSet) {
                    Text("${settings.imapUser} @ ${settings.imapHost}:${settings.imapPort}")
                    ButtonRow {
                        BigButton("Check e-mail now", Modifier.weight(1f)) { vm.syncEmail() }
                        BigOutlined("Settings", Modifier.weight(1f)) { imapOpen = true }
                    }
                } else {
                    Text("Not configured, so e-mail is skipped. Add IMAP host, user and an app password to enable.", style = MaterialTheme.typography.bodyMedium)
                    BigOutlined("Set up IMAP", Modifier.fillMaxWidth()) { imapOpen = true }
                }
            }
        }
        val totals = receipts.filter { it.total != null }.groupBy { it.currency.ifBlank { "?" } }
            .mapValues { (_, v) -> v.sumOf { it.total ?: 0.0 } }
        item { SectionTitle("Saved (${receipts.size})" + if (totals.isNotEmpty()) " · " + totals.entries.joinToString { "%.2f %s".format(Locale.ROOT, it.value, it.key) } else "") }
        items(receipts, key = { it.id }) { r ->
            InfoCard(Modifier.clickable { editing = r }) {
                Row {
                    Text(r.merchant.ifBlank { "(merchant?)" }, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text(r.total?.let { "%.2f %s".format(Locale.ROOT, it, r.currency) } ?: "(total?)", style = MaterialTheme.typography.titleMedium)
                }
                Text(r.dateMillis?.let { TimeText.full(it).substring(0, 10) } ?: r.dateText.ifBlank { "(date?)" })
                Text("Source: ${r.source.lowercase()} · tap to edit", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item { Text(" ") }
    }

    editing?.let { r -> ReceiptEditor(r, onDismiss = { editing = null }, onSave = { vm.update(it); editing = null }, onDelete = { vm.delete(it); editing = null }) }
    if (imapOpen) ImapDialog(settings.imapHost, settings.imapPort, settings.imapUser, onDismiss = { imapOpen = false },
        onSave = { h, p, u, pw -> vm.saveImap(h, p, u, pw); imapOpen = false }, onClear = { vm.clearImap(); imapOpen = false })
}

@Composable
private fun ReceiptEditor(r: Receipt, onDismiss: () -> Unit, onSave: (Receipt) -> Unit, onDelete: (Receipt) -> Unit) {
    var merchant by remember { mutableStateOf(r.merchant) }
    var date by remember { mutableStateOf(r.dateMillis?.let { TimeText.full(it).substring(0, 10) } ?: r.dateText) }
    var total by remember { mutableStateOf(r.total?.let { "%.2f".format(Locale.ROOT, it) } ?: "") }
    var currency by remember { mutableStateOf(r.currency) }
    var showRaw by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Receipt") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(merchant, { merchant = it }, label = { Text("Merchant") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(date, { date = it }, label = { Text("Date (yyyy-mm-dd or dd/mm/yyyy)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(total, { total = it }, label = { Text("Total") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(currency, { currency = it.uppercase().take(3) }, label = { Text("Currency (ILS, USD…)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (r.rawText.isNotBlank()) {
                    TextButton(onClick = { showRaw = !showRaw }) { Text(if (showRaw) "Hide recognized text" else "Show recognized text") }
                    if (showRaw) Text(r.rawText, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val parsedDate = ReceiptParser.findDate(date, Locale.getDefault().country == "US")
                onSave(
                    r.copy(
                        merchant = merchant.trim(),
                        dateText = date.trim(),
                        dateMillis = parsedDate.second ?: r.dateMillis.takeIf { date.isBlank() },
                        total = total.replace(',', '.').toDoubleOrNull(),
                        currency = currency.trim(),
                    )
                )
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onDelete(r) }) { Text("Delete") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun ImapDialog(host0: String, port0: Int, user0: String, onDismiss: () -> Unit, onSave: (String, Int, String, String) -> Unit, onClear: () -> Unit) {
    var host by remember { mutableStateOf(host0) }
    var port by remember { mutableStateOf(port0.toString()) }
    var user by remember { mutableStateOf(user0) }
    var pass by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("IMAP (read-only)") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Use an app password, not your main password. Stored only on this phone, encrypted.", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(host, { host = it }, label = { Text("Host, e.g. imap.gmail.com") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, label = { Text("Port (993)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(user, { user = it }, label = { Text("User / e-mail") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(pass, { pass = it }, label = { Text(if (host0.isNotBlank()) "App password (leave empty to keep)" else "App password") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onSave(host, port.toIntOrNull() ?: 993, user, pass) }) { Text("Save") } },
        dismissButton = {
            Row {
                if (host0.isNotBlank()) TextButton(onClick = onClear) { Text("Remove") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
