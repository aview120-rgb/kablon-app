package com.autoagent.app.status

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.autoagent.app.util.L
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class NetState(
    val online: Boolean = false,
    /** Android checked that this network really reaches the internet. */
    val validated: Boolean = false,
    val transport: String = "",
    val metered: Boolean = false,
) {
    fun label(): String = when {
        !online -> L.t("Offline", "לא מחובר")
        transport == "wifi" -> "Wi-Fi" + if (!validated) L.t(" (no internet)", " (ללא אינטרנט)") else ""
        transport == "cellular" -> L.t("Mobile data", "סלולרי") + if (!validated) L.t(" (no internet)", " (ללא אינטרנט)") else ""
        transport == "ethernet" -> L.t("Ethernet", "כבל רשת")
        transport == "vpn" -> "VPN"
        else -> L.t("Connected", "מחובר")
    }

    /** Short English code for the server dashboard. */
    fun code(): String = if (!online) "offline" else transport.ifBlank { "other" } + if (validated) "" else "-noinet"
}

/**
 * Follows the default network with a callback (no polling). [onBecameOnline] fires on every offline → online
 * transition, which is what triggers an immediate sync of everything collected offline.
 */
object NetworkMonitor {
    private val _state = MutableStateFlow(NetState())
    val state: StateFlow<NetState> = _state.asStateFlow()
    private var started = false
    private val listeners = mutableListOf<() -> Unit>()

    fun onBecameOnline(listener: () -> Unit) {
        synchronized(listeners) { listeners += listener }
    }

    fun start(context: Context) {
        if (started) return
        started = true
        val cm = context.getSystemService(ConnectivityManager::class.java)
        _state.value = read(cm, cm.activeNetwork)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = update(read(cm, network))
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = update(fromCaps(caps))
            override fun onLost(network: Network) = update(read(cm, cm.activeNetwork).takeIf { cm.activeNetwork != network } ?: NetState())
        }
        runCatching { cm.registerDefaultNetworkCallback(cb) }
    }

    private fun update(new: NetState) {
        val wasUsable = _state.value.online && _state.value.validated
        _state.value = new
        if (!wasUsable && new.online && new.validated) {
            synchronized(listeners) { listeners.toList() }.forEach { runCatching { it() } }
        }
    }

    private fun read(cm: ConnectivityManager, network: Network?): NetState {
        val caps = network?.let { runCatching { cm.getNetworkCapabilities(it) }.getOrNull() } ?: return NetState()
        return fromCaps(caps)
    }

    private fun fromCaps(caps: NetworkCapabilities): NetState = NetState(
        online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
        validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
        transport = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> ""
        },
        metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
    )
}
