package com.kushertil.filter.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted persistent state for the filter.
 *
 * Nothing security-critical is stored in plain text. Values are kept in
 * EncryptedSharedPreferences backed by the Android Keystore, so a casual
 * file dump of the app sandbox does not reveal the unlock state.
 */
object Prefs {

    private const val FILE = "kosher_til_secure"

    private const val KEY_ENABLED = "filter_enabled"
    private const val KEY_UNLOCK_UNTIL = "unlock_until_ms"
    private const val KEY_FAIL_COUNT = "fail_count"
    private const val KEY_LOCKOUT_UNTIL = "lockout_until_ms"

    // Managed-model fields.
    private const val KEY_SERVER_URL = "server_url"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_MANAGER_CONTACT = "manager_contact"
    private const val KEY_LAST_SYNC = "last_policy_sync_ms"
    private const val KEY_POLICY_VERSION = "policy_version"
    // Set once in production provisioning to burn the dev escape hatch.
    private const val KEY_MAINT_DISABLED = "maintenance_disabled"

    @Volatile
    private var sp: SharedPreferences? = null

    private fun prefs(ctx: Context): SharedPreferences {
        sp?.let { return it }
        synchronized(this) {
            sp?.let { return it }
            val key = MasterKey.Builder(ctx.applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val created = EncryptedSharedPreferences.create(
                ctx.applicationContext,
                FILE,
                key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            sp = created
            return created
        }
    }

    /** Whether the user asked the filter to run at all. */
    fun isEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ENABLED, false)

    fun setEnabled(ctx: Context, value: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_ENABLED, value).apply()

    /** Epoch millis until which filtering is temporarily released by a valid code. */
    fun unlockUntil(ctx: Context): Long = prefs(ctx).getLong(KEY_UNLOCK_UNTIL, 0L)

    fun setUnlockUntil(ctx: Context, untilMs: Long) =
        prefs(ctx).edit().putLong(KEY_UNLOCK_UNTIL, untilMs).apply()

    fun failCount(ctx: Context): Int = prefs(ctx).getInt(KEY_FAIL_COUNT, 0)

    fun setFailCount(ctx: Context, n: Int) =
        prefs(ctx).edit().putInt(KEY_FAIL_COUNT, n).apply()

    fun lockoutUntil(ctx: Context): Long = prefs(ctx).getLong(KEY_LOCKOUT_UNTIL, 0L)

    fun setLockoutUntil(ctx: Context, untilMs: Long) =
        prefs(ctx).edit().putLong(KEY_LOCKOUT_UNTIL, untilMs).apply()

    /** The filter should actively block right now. */
    fun filteringActiveNow(ctx: Context): Boolean =
        isEnabled(ctx) && System.currentTimeMillis() >= unlockUntil(ctx)

    // --- Managed model -------------------------------------------------------

    fun serverUrl(ctx: Context): String =
        prefs(ctx).getString(KEY_SERVER_URL, "")!!

    fun setServerUrl(ctx: Context, url: String) =
        prefs(ctx).edit().putString(KEY_SERVER_URL, url.trim()).apply()

    /** Stable per-install device id, generated on first use. */
    fun deviceId(ctx: Context): String {
        val existing = prefs(ctx).getString(KEY_DEVICE_ID, null)
        if (!existing.isNullOrBlank()) return existing
        val id = java.util.UUID.randomUUID().toString()
        prefs(ctx).edit().putString(KEY_DEVICE_ID, id).apply()
        return id
    }

    fun managerContact(ctx: Context): String =
        prefs(ctx).getString(KEY_MANAGER_CONTACT, "")!!

    fun setManagerContact(ctx: Context, value: String) =
        prefs(ctx).edit().putString(KEY_MANAGER_CONTACT, value).apply()

    fun lastSync(ctx: Context): Long = prefs(ctx).getLong(KEY_LAST_SYNC, 0L)

    fun setLastSync(ctx: Context, ms: Long) =
        prefs(ctx).edit().putLong(KEY_LAST_SYNC, ms).apply()

    fun policyVersion(ctx: Context): Int = prefs(ctx).getInt(KEY_POLICY_VERSION, 0)

    fun setPolicyVersion(ctx: Context, v: Int) =
        prefs(ctx).edit().putInt(KEY_POLICY_VERSION, v).apply()

    /** True when the development maintenance code has been permanently disabled. */
    fun maintenanceDisabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_MAINT_DISABLED, false)

    fun disableMaintenanceForever(ctx: Context) =
        prefs(ctx).edit().putBoolean(KEY_MAINT_DISABLED, true).apply()
}
