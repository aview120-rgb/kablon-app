package com.autoagent.app.status

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.autoagent.app.util.L
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class BatteryState(
    val percent: Int = -1,
    val charging: Boolean = false,
    val powerSave: Boolean = false,
    val temperatureC: Float? = null,
) {
    val known: Boolean get() = percent >= 0
    val low: Boolean get() = known && percent <= 15 && !charging

    fun label(): String = when {
        !known -> L.t("Battery ?", "סוללה ?")
        charging -> L.t("$percent% charging", "$percent% בטעינה")
        powerSave -> L.t("$percent% saver", "$percent% חיסכון")
        else -> "$percent%"
    }
}

/** Battery level/charging from the sticky ACTION_BATTERY_CHANGED broadcast (no polling, no permission). */
object BatteryMonitor {
    private val _state = MutableStateFlow(BatteryState())
    val state: StateFlow<BatteryState> = _state.asStateFlow()
    private var started = false

    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                _state.value = parse(app, intent)
            }
        }
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED).apply { addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED) }
        val sticky = ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        if (sticky != null) _state.value = parse(app, sticky)
    }

    /** One-shot read (e.g. inside a worker when the monitor is not running). */
    fun read(context: Context): BatteryState {
        val sticky = context.applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return sticky?.let { parse(context.applicationContext, it) } ?: _state.value
    }

    private fun parse(context: Context, i: Intent): BatteryState {
        if (i.action != Intent.ACTION_BATTERY_CHANGED) return _state.value.copy(powerSave = powerSave(context))
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        return BatteryState(
            percent = if (level < 0) -1 else level * 100 / scale,
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
            powerSave = powerSave(context),
            temperatureC = if (temp == Int.MIN_VALUE) null else temp / 10f,
        )
    }

    private fun powerSave(context: Context) = runCatching { context.getSystemService(PowerManager::class.java).isPowerSaveMode }.getOrDefault(false)
}
