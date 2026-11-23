package com.kablon.automation.core

import android.content.Context
import android.os.Environment
import android.os.StatFs
import java.io.File

/**
 * ניהול תקציב אחסון של 80GB (ולא 100GB) במכשיר 128GB.
 * אנדרואיד לא מאפשר שריון מחיצה – לכן זהו תקציב "רך" שהאפליקציה אוכפת על עצמה.
 */
object StorageBudget {
    /** 80GB בהגדרה עשרונית (כמו שיצרני הטלפונים סופרים). */
    const val BUDGET_BYTES: Long = 80L * 1000 * 1000 * 1000
    /** מרווח מינימלי שתמיד יישאר פנוי למערכת/לאפליקציות אחרות/לקבצים זמניים. */
    const val MIN_FREE_RESERVE: Long = 10L * 1000 * 1000 * 1000

    data class Snapshot(
        val deviceTotal: Long,
        val deviceFree: Long,
        val appUsed: Long,
    ) {
        val deviceUsed get() = deviceTotal - deviceFree
        val budgetLeft get() = (BUDGET_BYTES - appUsed).coerceAtLeast(0)
        /** כמה האפליקציה עוד יכולה לכתוב בפועל: המינימום בין יתרת התקציב לבין הפנוי פחות המרווח. */
        val usableNow get() = minOf(budgetLeft, (deviceFree - MIN_FREE_RESERVE).coerceAtLeast(0))
        val budgetPct get() = ((appUsed * 100.0) / BUDGET_BYTES).toInt().coerceIn(0, 100)
        val devicePct get() = if (deviceTotal == 0L) 0 else ((deviceUsed * 100.0) / deviceTotal).toInt()
        /** התקציב עצמו גדול מהשטח שאפשר להקצות בבטחה במכשיר. */
        val budgetExceedsDevice get() = BUDGET_BYTES > deviceTotal - MIN_FREE_RESERVE
    }

    fun snapshot(context: Context): Snapshot {
        val stat = StatFs(Environment.getDataDirectory().path)
        val total = stat.blockCountLong * stat.blockSizeLong
        val free = stat.availableBlocksLong * stat.blockSizeLong
        return Snapshot(total, free, appUsage(context))
    }

    fun appUsage(context: Context): Long {
        val dirs = listOfNotNull(
            context.filesDir, context.cacheDir,
            context.getExternalFilesDir(null), context.externalCacheDir,
        )
        return dirs.sumOf { dirSize(it) }
    }

    /** בדיקה לפני עבודה חדשה. מחזיר null אם מותר, או הודעת שגיאה בעברית. */
    fun checkCanWrite(context: Context, bytesNeeded: Long): String? {
        val s = snapshot(context)
        if (s.appUsed + bytesNeeded > BUDGET_BYTES)
            return "תקציב האחסון (80GB) של Kablon מלא: בשימוש ${format(s.appUsed)}. נקה פלטים ישנים במסך הראשי."
        if (s.deviceFree - bytesNeeded < MIN_FREE_RESERVE)
            return "נשאר מעט מדי מקום פנוי בטלפון (${format(s.deviceFree)}). Kablon שומר תמיד ${format(MIN_FREE_RESERVE)} פנויים למערכת."
        return null
    }

    /** ניקוי מטמון ופלטים ישנים. מחזיר כמה בייטים שוחררו. */
    fun cleanup(context: Context, olderThanMs: Long = 7L * 24 * 3600 * 1000): Long {
        var freed = 0L
        val now = System.currentTimeMillis()
        listOfNotNull(context.cacheDir, context.externalCacheDir).forEach { d ->
            d.listFiles()?.forEach { f -> freed += dirSize(f); f.deleteRecursively() }
        }
        context.getExternalFilesDir("output")?.listFiles()?.forEach { f ->
            if (now - f.lastModified() > olderThanMs) { freed += dirSize(f); f.deleteRecursively() }
        }
        return freed
    }

    fun dirSize(f: File): Long {
        if (!f.exists()) return 0
        if (f.isFile) return f.length()
        return f.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    fun format(bytes: Long): String {
        val gb = bytes / 1_000_000_000.0
        return when {
            gb >= 1 -> String.format(java.util.Locale.US, "%.1fGB", gb)
            bytes >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fMB", bytes / 1_000_000.0)
            else -> String.format(java.util.Locale.US, "%.0fKB", bytes / 1000.0)
        }
    }
}
