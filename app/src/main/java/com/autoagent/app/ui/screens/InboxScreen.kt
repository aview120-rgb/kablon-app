package com.autoagent.app.ui.screens

import com.autoagent.app.util.L
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.autoagent.app.ui.BigButton
import com.autoagent.app.ui.BigOutlined
import com.autoagent.app.ui.ButtonRow
import com.autoagent.app.ui.InfoCard
import com.autoagent.app.ui.InboxViewModel
import com.autoagent.app.ui.SectionTitle
import com.autoagent.app.ui.theme.Warn
import com.autoagent.app.util.TimeText

@Composable
fun InboxScreen(vm: InboxViewModel) {
    val entries by vm.entries.collectAsState()
    val summary by vm.summary.collectAsState()
    val hours by vm.windowHours.collectAsState()
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1 to L.t("1 h", "שעה"), 24 to L.t("24 h", "24 שעות"), 168 to L.t("7 days", "7 ימים")).forEach { (h, l) ->
                    FilterChip(hours == h, { vm.windowHours.value = h }, { Text(l) })
                }
            }
        }
        item {
            ButtonRow {
                BigButton(L.t("Summarize", "סכם"), Modifier.weight(1f)) { vm.summarize() }
                BigOutlined(L.t("Read aloud", "הקרא"), Modifier.weight(1f), enabled = summary != null) { vm.readAloud() }
            }
        }
        summary?.let { s ->
            item { SectionTitle(L.t("Summary: ${s.total} message(s), ${s.senders.size} sender(s)", "סיכום: ${s.total} הודעות, ${s.senders.size} שולחים")) }
            items(s.senders) { p ->
                InfoCard {
                    Row {
                        Text(p.sender, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Text(TimeText.format(p.lastAt))
                    }
                    Text(L.t("${p.app} · ${p.count} message(s)", "${p.app} · ${p.count} הודעות"), style = MaterialTheme.typography.bodyMedium)
                    p.asks.forEach { Text(L.t("Asks: $it", "מבקש: $it"), color = Warn) }
                    if (p.money.isNotEmpty()) Text(L.t("Money: ${p.money.joinToString()}", "סכומים: ${p.money.joinToString()}"), color = Warn)
                    if (p.asks.isEmpty()) Text(L.t("Latest: ${p.latest.take(160)}", "אחרונה: ${p.latest.take(160)}"), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        item { SectionTitle(L.t("Stored notifications (${entries.size})", "התראות שמורות (${entries.size})")) }
        if (entries.isEmpty()) item {
            Text(L.t("Nothing stored yet. Turn on Notification access in Permissions. Text is kept only on this phone (30 days).", "עדיין לא נשמר כלום. צריך להפעיל גישה להתראות במסך ההרשאות. הטקסט נשמר רק בטלפון הזה (30 יום)."))
        }
        items(entries.take(100), key = { it.id }) { e ->
            InfoCard(Modifier.fillMaxWidth()) {
                Row {
                    Text(e.sender, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text(TimeText.format(e.postedAt))
                }
                Text(e.appLabel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(e.text, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (entries.isNotEmpty()) item { BigOutlined(L.t("Delete all stored notifications", "מחיקת כל ההתראות השמורות"), Modifier.fillMaxWidth()) { vm.clear() } }
        item { Text(" ") }
    }
}
