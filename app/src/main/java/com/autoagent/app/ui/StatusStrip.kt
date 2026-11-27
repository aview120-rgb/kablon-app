package com.autoagent.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.autoagent.app.access.AccessibilityHealth
import com.autoagent.app.status.BatteryMonitor
import com.autoagent.app.status.NetworkMonitor
import com.autoagent.app.sync.SyncEngine
import com.autoagent.app.ui.theme.Bad
import com.autoagent.app.ui.theme.Ok
import com.autoagent.app.ui.theme.Warn
import com.autoagent.app.util.L

private val Neutral = Color(0xFF8A94A3)

/** Battery · network · accessibility service · server — shown under the top bar on every screen. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StatusStrip(go: (String) -> Unit) {
    val context = LocalContext.current
    val battery by BatteryMonitor.state.collectAsState()
    val net by NetworkMonitor.state.collectAsState()
    val health by AccessibilityHealth.health.collectAsState()
    val server by SyncEngine.state.collectAsState()
    LifecycleResumeEffect(Unit) {
        AccessibilityHealth.refresh(context)
        onPauseOrDispose { }
    }

    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        StatusChip(
            icon = when {
                battery.charging -> Icons.Filled.BatteryChargingFull
                battery.low -> Icons.Filled.BatteryAlert
                else -> Icons.Filled.BatteryFull
            },
            text = battery.label(),
            description = L.t("Battery", "סוללה"),
            color = when {
                !battery.known -> Neutral
                battery.low -> Bad
                battery.percent < 30 && !battery.charging -> Warn
                else -> Ok
            },
        ) {}
        StatusChip(
            icon = when {
                !net.online -> Icons.Filled.WifiOff
                net.transport == "cellular" -> Icons.Filled.SignalCellularAlt
                else -> Icons.Filled.Wifi
            },
            text = net.label(),
            description = L.t("Network", "רשת"),
            color = when {
                !net.online -> Bad
                !net.validated -> Warn
                else -> Ok
            },
        ) {}
        StatusChip(
            icon = Icons.Filled.Accessibility,
            text = L.t("Accessibility: ", "נגישות: ") + health.label(),
            description = L.t("Accessibility service", "שירות נגישות"),
            color = when (health.state) {
                AccessibilityHealth.State.READY -> Ok
                AccessibilityHealth.State.ENABLED_NOT_BOUND -> Warn
                AccessibilityHealth.State.DISABLED -> Bad
            },
        ) { go("sync") }
        StatusChip(
            icon = when (server.phase) {
                SyncEngine.Phase.CONNECTED -> Icons.Filled.CloudDone
                SyncEngine.Phase.OFFLINE, SyncEngine.Phase.ERROR, SyncEngine.Phase.AUTH_ERROR -> Icons.Filled.CloudOff
                else -> Icons.Filled.Cloud
            },
            text = L.t("Server: ", "שרת: ") + server.label(),
            description = L.t("Server connection", "חיבור לשרת"),
            color = when (server.phase) {
                SyncEngine.Phase.CONNECTED -> Ok
                SyncEngine.Phase.CONNECTING, SyncEngine.Phase.OFFLINE -> Warn
                SyncEngine.Phase.ERROR, SyncEngine.Phase.AUTH_ERROR -> Bad
                SyncEngine.Phase.DISABLED, SyncEngine.Phase.NOT_CONFIGURED -> Neutral
            },
        ) { go("sync") }
    }
}

@Composable
private fun StatusChip(icon: ImageVector, text: String, description: String, color: Color, onClick: () -> Unit) {
    Row(
        Modifier
            .heightIn(min = 32.dp)
            .background(color.copy(alpha = 0.16f), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$description: $text" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}
