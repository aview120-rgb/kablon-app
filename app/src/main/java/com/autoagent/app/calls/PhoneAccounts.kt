package com.autoagent.app.calls

import android.Manifest
import android.annotation.SuppressLint
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat

object PhoneAccounts {
    private const val TEST_ID = "autoagent-test-line"

    fun testHandle(context: Context) =
        PhoneAccountHandle(ComponentName(context, TestConnectionService::class.java), TEST_ID)

    /** Registers a self-managed PhoneAccount used for the in-app assistant test call. */
    fun register(context: Context) {
        runCatching {
            val account = PhoneAccount.builder(testHandle(context), "AutoAgent test line")
                .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
                .addSupportedUriScheme(PhoneAccount.SCHEME_TEL)
                .build()
            context.getSystemService(TelecomManager::class.java).registerPhoneAccount(account)
        }
    }

    fun isDefaultDialer(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 29) {
            context.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_DIALER)
        } else {
            context.getSystemService(TelecomManager::class.java).defaultDialerPackage == context.packageName
        }

    fun canCall(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED

    /** Places a real carrier call through Telecom. Returns an error message or null on success. */
    @SuppressLint("MissingPermission")
    fun placeCall(context: Context, number: String): String? {
        val clean = number.trim()
        if (clean.filter { it.isDigit() }.length < 3) return "That does not look like a phone number"
        if (!canCall(context)) return "Phone permission is off"
        return try {
            context.getSystemService(TelecomManager::class.java)
                .placeCall(Uri.fromParts(PhoneAccount.SCHEME_TEL, clean, null), Bundle())
            null
        } catch (e: SecurityException) {
            "Android refused the call: ${e.message}"
        }
    }

    /** Starts a self-managed incoming test call that runs the assistant script on this phone's speaker. */
    fun startTestCall(context: Context): String? {
        val tm = context.getSystemService(TelecomManager::class.java)
        val handle = testHandle(context)
        register(context)
        return try {
            if (!tm.isIncomingCallPermitted(handle)) return "Android does not allow a test call right now (another call is active)"
            val extras = Bundle().apply {
                putParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS, Uri.fromParts(PhoneAccount.SCHEME_TEL, "TEST", null))
            }
            tm.addNewIncomingCall(handle, extras)
            null
        } catch (e: SecurityException) {
            "Android refused the test call: ${e.message}"
        }
    }
}
