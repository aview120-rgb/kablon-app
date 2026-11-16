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
}
