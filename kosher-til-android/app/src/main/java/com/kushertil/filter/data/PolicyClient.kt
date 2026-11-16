package com.kushertil.filter.data

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Pulls the device policy from the remote management server (the
 * "Netsmart-style" control plane). The server is the single source of truth
 * for whether filtering is on and for any temporary release window.
 *
 * Fail-closed by design: if the server cannot be reached, the last known
 * policy is kept. A device that was never provisioned defaults to
 * filtering ON once [Prefs.setEnabled] has been turned on by provisioning.
 *
 * Expected response from GET {server}/api/device/{id}/policy :
 * {
 *   "version": 7,
 *   "enabled": true,
 *   "unlockUntil": 0,           // epoch millis; >now = temporarily released
 *   "managerContact": "050-0000000"
 * }
 */
object PolicyClient {

    private const val TAG = "PolicyClient"
    private const val TIMEOUT_MS = 8000

    data class Result(val ok: Boolean, val changed: Boolean)

    fun sync(ctx: Context): Result {
        val base = Prefs.serverUrl(ctx)
        if (base.isBlank()) {
            // Not provisioned with a server yet: nothing to sync, keep local state.
            return Result(ok = false, changed = false)
        }
        val id = Prefs.deviceId(ctx)
        val url = base.trimEnd('/') + "/api/device/" + id + "/policy"
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Accept", "application/json")
                setRequestProperty("X-Device-Id", id)
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "policy sync http $code")
                return Result(ok = false, changed = false)
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val changed = applyPolicy(ctx, json)
            Prefs.setLastSync(ctx, System.currentTimeMillis())
            Result(ok = true, changed = changed)
        } catch (e: Exception) {
            Log.w(TAG, "policy sync failed: ${e.message}")
            Result(ok = false, changed = false)
        } finally {
            conn?.disconnect()
        }
    }

    private fun applyPolicy(ctx: Context, json: JSONObject): Boolean {
        val version = json.optInt("version", Prefs.policyVersion(ctx))
        // Ignore stale/older policy versions.
        if (version < Prefs.policyVersion(ctx)) return false

        var changed = false
        val enabled = json.optBoolean("enabled", Prefs.isEnabled(ctx))
        if (enabled != Prefs.isEnabled(ctx)) {
            Prefs.setEnabled(ctx, enabled); changed = true
        }
        val unlockUntil = json.optLong("unlockUntil", Prefs.unlockUntil(ctx))
        if (unlockUntil != Prefs.unlockUntil(ctx)) {
            Prefs.setUnlockUntil(ctx, unlockUntil); changed = true
        }
        json.optString("managerContact").takeIf { it.isNotBlank() }?.let {
            if (it != Prefs.managerContact(ctx)) {
                Prefs.setManagerContact(ctx, it); changed = true
            }
        }
        Prefs.setPolicyVersion(ctx, version)
        return changed
    }

    /** Notify the server that the user requested a release (manager decides). */
    fun requestUnlock(ctx: Context, reason: String): Boolean {
        val base = Prefs.serverUrl(ctx)
        if (base.isBlank()) return false
        val id = Prefs.deviceId(ctx)
        val url = base.trimEnd('/') + "/api/device/" + id + "/unlock-request"
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-Device-Id", id)
            }
            val payload = JSONObject()
                .put("deviceId", id)
                .put("reason", reason)
                .put("ts", System.currentTimeMillis())
                .toString()
            conn.outputStream.use { it.write(payload.toByteArray()) }
            conn.responseCode in 200..299
        } catch (e: Exception) {
            Log.w(TAG, "unlock request failed: ${e.message}")
            false
        } finally {
            conn?.disconnect()
        }
    }
}
