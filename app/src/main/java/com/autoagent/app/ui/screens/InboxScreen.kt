package com.autoagent.app.ui.screens

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
                listOf(1 to "1 h", 24 to "24 h", 168 to "7 days").forEach { (h, l) ->
                    FilterChip(hours == h, { vm.windowHours.value = h }, { Text(l) })
                }
            }
        }
        item {
            ButtonRow {
                BigButton("Summarize", Modifier.weight(1f)) { vm.summarize() }
                BigOutlined("Read aloud", Modifier.weight(1f), enabled = summary != null) { vm.readAloud() }
            }
        }
        summary?.let { s ->
            item { SectionTitle("Summary: ${s.total} message(s), ${s.senders.size} sender(s)") }
            items(s.senders) { p ->
                InfoCard {
                    Row {
                        Text("${p.sender}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Text(TimeText.format(p.lastAt))
                    }
                    Text("${p.app} · ${p.count} message(s)", style = MaterialTheme.typography.bodyMedium)
                    p.asks.forEach { Text("Asks: $it", color = Warn) }
                    if (p.money.isNotEmpty()) Text("Money: ${p.money.joinToString()}", color = Warn)
                    if (p.asks.isEmpty()) Text("Latest: ${p.latest.take(160)}", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        item { SectionTitle("Stored notifications (${entries.size})") }
        if (entries.isEmpty()) item {
            Text("Nothing stored yet. Turn on Notification access in Permissions. Text is kept only on this phone (30 days).")
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
        if (entries.isNotEmpty()) item { BigOutlined("Delete all stored notifications", Modifier.fillMaxWidth()) { vm.clear() } }
        item { Text(" ") }
    }
}
