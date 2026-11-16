package com.kushertil.filter.data

import android.content.Context

/**
 * Domain + app blocklist.
 *
 * This ships with a small, honest seed list. A production filter maintains
 * its lists on a server and refreshes them; see README (Blocklist sources).
 * Matching is suffix based: "example.com" also blocks "ads.example.com".
 */
object Blocklist {

    // Seed adult/explicit domains (illustrative, non-exhaustive).
    private val seedDomains: Set<String> = setOf(
        "pornhub.com",
        "xvideos.com",
        "xnxx.com",
        "redtube.com",
        "youporn.com",
        "onlyfans.com",
        "chaturbate.com",
        "stripchat.com"
    )

    // Packages blocked at the app level by the accessibility service.
    private val seedBlockedApps: Set<String> = setOf(
        // e.g. an app store / browser the policy forbids. Left minimal on purpose.
    )

    @Volatile
    private var userDomains: MutableSet<String> = mutableSetOf()

    @Volatile
    private var userApps: MutableSet<String> = mutableSetOf()

    fun load(ctx: Context) {
        val sp = ctx.getSharedPreferences("blocklist", Context.MODE_PRIVATE)
        userDomains = sp.getStringSet("domains", emptySet())!!.toMutableSet()
        userApps = sp.getStringSet("apps", emptySet())!!.toMutableSet()
    }

    private fun persist(ctx: Context) {
        ctx.getSharedPreferences("blocklist", Context.MODE_PRIVATE).edit()
            .putStringSet("domains", userDomains)
            .putStringSet("apps", userApps)
            .apply()
    }

    fun addDomain(ctx: Context, domain: String) {
        userDomains.add(domain.trim().lowercase())
        persist(ctx)
    }

    fun addApp(ctx: Context, pkg: String) {
        userApps.add(pkg.trim())
        persist(ctx)
    }

    fun blockedApps(): Set<String> = seedBlockedApps + userApps

    /** True when [host] (or any parent domain) is blocked. */
    fun isDomainBlocked(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val h = host.trim().lowercase().removeSuffix(".")
        val all = seedDomains + userDomains
        if (all.contains(h)) return true
        var idx = h.indexOf('.')
        while (idx >= 0 && idx < h.length - 1) {
            val suffix = h.substring(idx + 1)
            if (all.contains(suffix)) return true
            idx = h.indexOf('.', idx + 1)
        }
        return false
    }

    /** Extract a hostname from a browser URL/address-bar string, best effort. */
    fun hostFromUrlText(text: String?): String? {
        if (text.isNullOrBlank()) return null
        var t = text.trim()
        val scheme = t.indexOf("://")
        if (scheme >= 0) t = t.substring(scheme + 3)
        t = t.substringBefore('/').substringBefore('?').substringBefore(' ')
        t = t.substringAfter('@', t) // strip user-info
        t = t.substringBefore(':')   // strip port
        if (t.isBlank() || !t.contains('.')) return null
        return t.lowercase()
    }
}
