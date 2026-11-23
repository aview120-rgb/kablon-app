package com.kablon.automation.store

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.content.pm.PackageManager

enum class ToolCategory(val title: String) {
    SOCIAL("רשתות חברתיות"), AI("כלי AI"), EDIT("עריכת וידאו"), TERMINAL("Termux / מסוף"), UTIL("כלי עזר")
}

data class Tool(
    val id: String,
    val name: String,
    val packages: List<String>,
    val category: ToolCategory,
    val description: String,
    /** קישור התקנה (Play / F-Droid / אתר). */
    val installUrl: String,
    /** האם קיימת זרימת אוטומציה גלויה עבור הכלי. */
    val automatable: Boolean = false,
    val isAiChat: Boolean = false,
)

data class ToolStatus(val tool: Tool, val installedPackage: String?, val version: String?) {
    val installed get() = installedPackage != null
}

/** קטלוג ה"חנות" הפנימית – האפליקציות והכלים שהמערכת עובדת מולם. */
object ToolCatalog {
    val tools = listOf(
        Tool("tiktok", "TikTok", listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill"), ToolCategory.SOCIAL,
            "העלאת תמונות/סרטונים, בחירת סאונד וכיתוב – זרימה אוטומטית גלויה.",
            "market://details?id=com.zhiliaoapp.musically", automatable = true),
        Tool("chatgpt", "ChatGPT", listOf("com.openai.chatgpt"), ToolCategory.AI,
            "שליחת הנחיה/תמונה, קבלת הצעות לכיתוב וסאונד, עריכת תמונה.",
            "market://details?id=com.openai.chatgpt", automatable = true, isAiChat = true),
        Tool("gemini", "Google Gemini", listOf("com.google.android.apps.bard"), ToolCategory.AI,
            "מודל AI של Google – הנחיות, ניתוח תמונה, הצעות.",
            "market://details?id=com.google.android.apps.bard", automatable = true, isAiChat = true),
        Tool("claude", "Claude", listOf("com.anthropic.claude"), ToolCategory.AI,
            "מודל AI של Anthropic – כתיבה וניתוח.",
            "market://details?id=com.anthropic.claude", automatable = true, isAiChat = true),
        Tool("copilot", "Microsoft Copilot", listOf("com.microsoft.copilot"), ToolCategory.AI,
            "עוזר AI של Microsoft, כולל יצירת תמונות.",
            "market://details?id=com.microsoft.copilot", automatable = true, isAiChat = true),
        Tool("perplexity", "Perplexity", listOf("ai.perplexity.app.android"), ToolCategory.AI,
            "חיפוש מבוסס AI – טרנדים וסאונדים פופולריים.",
            "market://details?id=ai.perplexity.app.android", automatable = true, isAiChat = true),
        Tool("capcut", "CapCut", listOf("com.lemon.lvoverseas"), ToolCategory.EDIT,
            "עריכת וידאו מתקדמת (עריכה ידנית).",
            "market://details?id=com.lemon.lvoverseas"),
        Tool("termux", "Termux", listOf("com.termux"), ToolCategory.TERMINAL,
            "מסוף לינוקס: ffmpeg, סקריפטים, עיבוד וידאו מתקדם. מומלץ להתקין מ-F-Droid (הגרסה ב-Play ישנה).",
            "https://f-droid.org/packages/com.termux/", automatable = true),
        Tool("termux_api", "Termux:API", listOf("com.termux.api"), ToolCategory.TERMINAL,
            "תוסף ל-Termux לגישה ליכולות הטלפון מסקריפטים. התקן מאותו מקור כמו Termux.",
            "https://f-droid.org/packages/com.termux.api/"),
        Tool("fdroid", "F-Droid", listOf("org.fdroid.fdroid"), ToolCategory.UTIL,
            "חנות קוד פתוח – המקור המומלץ ל-Termux.",
            "https://f-droid.org/F-Droid.apk"),
        Tool("files", "Files by Google", listOf("com.google.android.apps.nbu.files"), ToolCategory.UTIL,
            "ניהול קבצים ופינוי מקום.",
            "market://details?id=com.google.android.apps.nbu.files"),
    )

    fun byId(id: String) = tools.firstOrNull { it.id == id }
    val aiChatTools get() = tools.filter { it.isAiChat }

    fun installedPackage(context: Context, tool: Tool): String? =
        tool.packages.firstOrNull { context.packageManager.getLaunchIntentForPackage(it) != null }

    fun status(context: Context, tool: Tool): ToolStatus {
        val pkg = installedPackage(context, tool)
        val ver = pkg?.let {
            try {
                if (Build.VERSION.SDK_INT >= 33)
                    context.packageManager.getPackageInfo(it, PackageManager.PackageInfoFlags.of(0)).versionName
                else @Suppress("DEPRECATION") context.packageManager.getPackageInfo(it, 0).versionName
            } catch (_: Exception) { null }
        }
        return ToolStatus(tool, pkg, ver)
    }

    fun open(context: Context, tool: Tool): Boolean {
        val pkg = installedPackage(context, tool) ?: return false
        val i = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    fun install(context: Context, tool: Tool) {
        val uri = Uri.parse(tool.installUrl)
        val i = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(i)
        } catch (_: Exception) {
            // אין Play Store – נפתח בדפדפן
            val web = tool.installUrl.replace("market://details?id=", "https://play.google.com/store/apps/details?id=")
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(web)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun openAppInfo(context: Context, pkg: String) {
        context.startActivity(
            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
