package com.kablon.automation.store

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * שילוב מול Termux דרך RUN_COMMAND.
 * דרישות: Termux מותקן (מ-F-Droid), ו-allow-external-apps=true בקובץ ~/.termux/termux.properties,
 * והרשאת "Run commands in Termux environment" ל-Kablon.
 */
object TermuxBridge {
    const val PKG = "com.termux"
    const val PERMISSION = "com.termux.permission.RUN_COMMAND"
    private const val BASH = "/data/data/com.termux/files/usr/bin/bash"
    private const val HOME = "/data/data/com.termux/files/home"

    /** פקודת הכנה חד-פעמית שמדביקים ב-Termux. */
    const val SETUP_COMMAND =
        "mkdir -p ~/.termux && grep -q '^allow-external-apps' ~/.termux/termux.properties 2>/dev/null || " +
            "echo 'allow-external-apps=true' >> ~/.termux/termux.properties; " +
            "termux-reload-settings; pkg update -y && pkg install -y ffmpeg; termux-setup-storage"

    fun isInstalled(context: Context) = context.packageManager.getLaunchIntentForPackage(PKG) != null

    fun hasPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED

    /**
     * מריץ פקודת bash ב-Termux. background=false => נפתח חלון מסוף גלוי.
     * מחזיר null בהצלחה או הודעת שגיאה.
     */
    fun run(context: Context, command: String, background: Boolean = false): String? {
        if (!isInstalled(context)) return "Termux לא מותקן. התקן אותו מהחנות הפנימית (F-Droid)."
        if (!hasPermission(context)) return "חסרה הרשאת RUN_COMMAND ל-Kablon (אשר אותה כשתתבקש)."
        val i = Intent().apply {
            setClassName(PKG, "com.termux.app.RunCommandService")
            action = "com.termux.RUN_COMMAND"
            putExtra("com.termux.RUN_COMMAND_PATH", BASH)
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", command))
            putExtra("com.termux.RUN_COMMAND_WORKDIR", HOME)
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", background)
            putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
        }
        return try {
            context.startService(i)
            null
        } catch (e: Exception) {
            try {
                ContextCompat.startForegroundService(context, i)
                null
            } catch (e2: Exception) {
                "הפעלת Termux נכשלה: ${e2.message}. ודא ש-allow-external-apps=true מוגדר (כפתור 'הגדרת Termux')."
            }
        }
    }

    /** פקודת ffmpeg לטשטוש אזור סימן מים בסרטון (delogo). */
    fun delogoCommand(input: String, output: String, x: Int, y: Int, w: Int, h: Int): String =
        "ffmpeg -y -i '${input}' -vf \"delogo=x=$x:y=$y:w=$w:h=$h\" -c:a copy '${output}' && " +
            "echo '✅ נשמר: $output' && termux-media-scan '${output}' 2>/dev/null; echo 'סגור את החלון וחזור ל-Kablon'"
}
