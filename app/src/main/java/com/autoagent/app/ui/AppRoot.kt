package com.autoagent.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.autoagent.app.LaunchRequest
import com.autoagent.app.ui.screens.AutoAnswerScreen
import com.autoagent.app.ui.screens.HomeScreen
import com.autoagent.app.ui.screens.InboxScreen
import com.autoagent.app.ui.screens.PermissionsScreen
import com.autoagent.app.ui.screens.ReceiptsScreen
import com.autoagent.app.ui.screens.SchedulesScreen
import com.autoagent.app.ui.screens.VoiceScreen

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("home", "Home", Icons.Filled.Home),
    Tab("schedules", "Schedules", Icons.Filled.Schedule),
    Tab("autoanswer", "Calls", Icons.Filled.Call),
    Tab("inbox", "Inbox", Icons.Filled.Inbox),
    Tab("voice", "Voice", Icons.Filled.Mic),
)

private val titles = mapOf(
    "home" to "AutoAgent",
    "schedules" to "Schedules",
    "autoanswer" to "Auto-answer",
    "inbox" to "Inbox summaries",
    "receipts" to "Receipts",
    "voice" to "Voice",
    "permissions" to "Permissions",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(request: LaunchRequest) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val current = entry?.destination?.route ?: "home"
    val receiptsVm: ReceiptsViewModel = viewModel()
    val homeVm: HomeViewModel = viewModel()

    fun go(route: String) {
        nav.navigate(route) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    LaunchedEffect(request.nonce) {
        request.dial?.let { homeVm.dialText.value = it }
        request.receiptFile?.let { receiptsVm.ingest(it, request.receiptAudio) }
        request.route?.let { if (it in titles) go(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(titles[current] ?: "AutoAgent") },
                actions = {
                    IconButton(onClick = { go("permissions") }) {
                        Icon(Icons.Filled.VerifiedUser, contentDescription = "Permissions")
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                tabs.forEach { t ->
                    NavigationBarItem(
                        selected = current == t.route,
                        onClick = { go(t.route) },
                        icon = { Icon(t.icon, contentDescription = t.label) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = "home", modifier = Modifier.padding(padding)) {
            composable("home") { HomeScreen(homeVm, ::go) }
            composable("schedules") { SchedulesScreen(viewModel()) }
            composable("autoanswer") { AutoAnswerScreen(viewModel()) }
            composable("inbox") { InboxScreen(viewModel()) }
            composable("receipts") { ReceiptsScreen(receiptsVm) }
            composable("voice") { VoiceScreen(viewModel()) }
            composable("permissions") { PermissionsScreen() }
        }
    }
}
