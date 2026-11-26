package com.autoagent.app.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

object Contacts {
    fun hasPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** Returns the contact display name for [number], or null if not a contact. Requires READ_CONTACTS. */
    fun nameForNumber(context: Context, number: String): String? {
        if (number.isBlank() || !hasPermission(context)) return null
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        return context.contentResolver.query(
            uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null,
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }

    data class Match(val name: String, val number: String)

    /** Contacts whose display name matches [name] (case-insensitive). Used by voice "call <name>". */
    fun findByName(context: Context, name: String): List<Match> {
        if (name.isBlank() || !hasPermission(context)) return emptyList()
        val result = mutableListOf<Match>()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
            ),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%${name.trim()}%"),
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                val n = c.getString(0) ?: continue
                val num = c.getString(1) ?: continue
                result += Match(n, num)
            }
        }
        val exact = result.filter { it.name.equals(name.trim(), ignoreCase = true) }
        val pool = exact.ifEmpty { result }
        return pool.distinctBy { it.name.lowercase() + normalize(it.number) }
    }

    /** Phone numbers for a contact URI returned by the system contact picker. */
    fun numbersForContactUri(context: Context, contactUri: Uri): Pair<String, List<String>>? {
        if (!hasPermission(context)) return null
        var id: String? = null
        var name = ""
        context.contentResolver.query(
            contactUri,
            arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME),
            null, null, null,
        )?.use { c -> if (c.moveToFirst()) { id = c.getString(0); name = c.getString(1) ?: "" } }
        val cid = id ?: return null
        val numbers = mutableListOf<String>()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
            arrayOf(cid), null,
        )?.use { c -> while (c.moveToNext()) c.getString(0)?.let { numbers += it } }
        return name to numbers.distinctBy { normalize(it) }
    }

    fun normalize(number: String): String = number.filter { it.isDigit() || it == '+' }
}
