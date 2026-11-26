package com.autoagent.app.ui.screens

import com.autoagent.app.util.L
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
            if (f != null) vm.ingest(f.absolutePath, false) else vm.message.value = L.t("Could not read that image", "לא הצלחתי לקרוא את התמונה")
        }
    }
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            val f = runCatching { ReceiptIngest.copyToStorage(context, uri, context.contentResolver.getType(uri)) }.getOrNull()
            if (f != null) vm.ingest(f.absolutePath, true) else vm.message.value = L.t("Could not read that audio file", "לא הצלחתי לקרוא את קובץ הקול")
        }
    }
    val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) vm.dictate() else vm.message.value = L.t("Microphone permission is needed to dictate", "צריך הרשאה למיקרופון כדי להכתיב")
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            InfoCard(Modifier.padding(top = 12.dp)) {
                Text(L.t("Share a receipt photo or voice note to AutoAgent from any app, or add one here. OCR and speech-to-text run on this phone.", "אפשר לשתף צילום קבלה או הקלטה ל-AutoAgent מכל אפליקציה, או להוסיף כאן. זיהוי הטקסט והדיבור רצים בטלפון עצמו. שים לב: זיהוי טקסט מתמונה תומך רק באותיות לטיניות; קבלה בעברית עדיף להכתיב."), style = MaterialTheme.typography.bodyMedium)
                ButtonRow {
                    BigButton(L.t("Photo", "תמונה"), Modifier.weight(1f), enabled = !busy) { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                    BigButton(L.t("Voice note", "הקלטה"), Modifier.weight(1f), enabled = !busy) { pickAudio.launch("audio/*") }
                }
                ButtonRow {
                    BigOutlined(L.t("Dictate receipt", "הכתבת קבלה"), Modifier.weight(1f), enabled = !busy) { micPerm.launch(Manifest.permission.RECORD_AUDIO) }
                    BigOutlined(L.t("Type it", "הקלדה"), Modifier.weight(1f)) { vm.addManual() }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (partial.isNotBlank()) Text("\"$partial\"")
                msg?.let { Text(it, color = Warn, modifier = Modifier.clickable { vm.message.value = null }) }
            }
        }
        item {
            InfoCard {
                Text(L.t("E-mail receipts (optional)", "קבלות מהדואר (לא חובה)"), style = MaterialTheme.typography.titleMedium)
                if (settings.imapPasswordSet) {
                    Text("${settings.imapUser} @ ${settings.imapHost}:${settings.imapPort}")
                    ButtonRow {
                        BigButton(L.t("Check e-mail now", "בדיקת הדואר עכשיו"), Modifier.weight(1f)) { vm.syncEmail() }
                        BigOutlined(L.t("Settings", "הגדרות"), Modifier.weight(1f)) { imapOpen = true }
                    }
                } else {
                    Text(L.t("Not configured, so e-mail is skipped. Add IMAP host, user and an app password to enable.", "לא מוגדר, ולכן לא נבדק דואר. כדי להפעיל צריך להוסיף שרת IMAP, משתמש וסיסמת אפליקציה."), style = MaterialTheme.typography.bodyMedium)
                    BigOutlined(L.t("Set up IMAP", "הגדרת IMAP"), Modifier.fillMaxWidth()) { imapOpen = true }
                }
            }
        }
        val totals = receipts.filter { it.total != null }.groupBy { it.currency.ifBlank { "?" } }
            .mapValues { (_, v) -> v.sumOf { it.total ?: 0.0 } }
        item { SectionTitle(L.t("Saved (${receipts.size})", "נשמרו (${receipts.size})") + if (totals.isNotEmpty()) " · " + totals.entries.joinToString { "%.2f %s".format(Locale.ROOT, it.value, it.key) } else "") }
        items(receipts, key = { it.id }) { r ->
            InfoCard(Modifier.clickable { editing = r }) {
                Row {
                    Text(r.merchant.ifBlank { L.t("(merchant?)", "(בית עסק?)") }, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text(r.total?.let { "%.2f %s".format(Locale.ROOT, it, r.currency) } ?: "(total?)", style = MaterialTheme.typography.titleMedium)
                }
                Text(r.dateMillis?.let { TimeText.day(it) } ?: r.dateText.ifBlank { L.t("(date?)", "(תאריך?)") })
                Text(L.t("Source: ", "מקור: ") + sourceLabel(r.source) + L.t(" · tap to edit", " · הקש לעריכה"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item { Text(" ") }
    }

    editing?.let { r -> ReceiptEditor(r, onDismiss = { editing = null }, onSave = { vm.update(it); editing = null }, onDelete = { vm.delete(it); editing = null }) }
    if (imapOpen) ImapDialog(settings.imapHost, settings.imapPort, settings.imapUser, onDismiss = { imapOpen = false },
        onSave = { h, p, u, pw -> vm.saveImap(h, p, u, pw); imapOpen = false }, onClear = { vm.clearImap(); imapOpen = false })
}

private fun sourceLabel(s: String) = when (s) {
    com.autoagent.app.data.ReceiptSource.IMAGE -> L.t("photo", "תמונה")
    com.autoagent.app.data.ReceiptSource.VOICE -> L.t("voice", "קול")
    com.autoagent.app.data.ReceiptSource.EMAIL -> L.t("e-mail", "דואר")
    else -> L.t("manual", "ידני")
}

@Composable
private fun ReceiptEditor(r: Receipt, onDismiss: () -> Unit, onSave: (Receipt) -> Unit, onDelete: (Receipt) -> Unit) {
    var merchant by remember { mutableStateOf(r.merchant) }
    var date by remember { mutableStateOf(r.dateMillis?.let { TimeText.day(it) } ?: r.dateText) }
    var total by remember { mutableStateOf(r.total?.let { "%.2f".format(Locale.ROOT, it) } ?: "") }
    var currency by remember { mutableStateOf(r.currency) }
    var showRaw by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(L.t("Receipt", "קבלה")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(merchant, { merchant = it }, label = { Text(L.t("Merchant", "בית העסק")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(date, { date = it }, label = { Text(L.t("Date (yyyy-mm-dd or dd/mm/yyyy)", "תאריך (dd/mm/yyyy או yyyy-mm-dd)")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(total, { total = it }, label = { Text(L.t("Total", "סכום")) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(currency, { currency = it.uppercase().take(3) }, label = { Text(L.t("Currency (ILS, USD…)", "מטבע (ILS, USD…)")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (r.rawText.isNotBlank()) {
                    TextButton(onClick = { showRaw = !showRaw }) { Text(if (showRaw) L.t("Hide recognized text", "הסתר את הטקסט שזוהה") else L.t("Show recognized text", "הצג את הטקסט שזוהה")) }
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
            }) { Text(L.t("Save", "שמירה")) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onDelete(r) }) { Text(L.t("Delete", "מחיקה")) }
                TextButton(onClick = onDismiss) { Text(L.t("Cancel", "ביטול")) }
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
        title = { Text(L.t("IMAP (read-only)", "IMAP (קריאה בלבד)")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(L.t("Use an app password, not your main password. Stored only on this phone, encrypted.", "צריך סיסמת אפליקציה, לא את הסיסמה הראשית. נשמרת רק בטלפון הזה, מוצפנת."), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(host, { host = it }, label = { Text(L.t("Host, e.g. imap.gmail.com", "שרת, למשל imap.gmail.com")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, label = { Text(L.t("Port (993)", "פורט (993)")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(user, { user = it }, label = { Text(L.t("User / e-mail", "משתמש / מייל")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(pass, { pass = it }, label = { Text(if (host0.isNotBlank()) L.t("App password (leave empty to keep)", "סיסמת אפליקציה (ריק = להשאיר)") else L.t("App password", "סיסמת אפליקציה")) },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onSave(host, port.toIntOrNull() ?: 993, user, pass) }) { Text(L.t("Save", "שמירה")) } },
        dismissButton = {
            Row {
                if (host0.isNotBlank()) TextButton(onClick = onClear) { Text(L.t("Remove", "הסרה")) }
                TextButton(onClick = onDismiss) { Text(L.t("Cancel", "ביטול")) }
            }
        },
    )
}
