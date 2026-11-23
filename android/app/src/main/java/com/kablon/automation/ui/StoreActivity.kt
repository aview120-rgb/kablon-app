package com.kablon.automation.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kablon.automation.R
import com.kablon.automation.store.ToolCatalog
import com.kablon.automation.store.ToolStatus
import com.kablon.automation.store.TermuxBridge

/** "חנות" פנימית: רשימת הכלים, סטטוס זמינות, פתיחה, התקנה ושילוב Termux. */
class StoreActivity : AppCompatActivity() {
    private lateinit var adapter: ToolAdapter
    private var pendingCommand: String? = null

    private val termuxPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) pendingCommand?.let { runTermux(it) } else toast("ללא ההרשאה Kablon לא יכול להריץ פקודות ב-Termux")
        pendingCommand = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_store)
        adapter = ToolAdapter()
        findViewById<RecyclerView>(R.id.toolList).apply {
            layoutManager = LinearLayoutManager(this@StoreActivity)
            adapter = this@StoreActivity.adapter
        }
    }

    override fun onResume() {
        super.onResume()
        val items = ToolCatalog.tools.map { ToolCatalog.status(this, it) }.sortedBy { it.tool.category.ordinal }
        adapter.submit(items)
        findViewById<TextView>(R.id.storeSummary).text =
            "מותקנים ${items.count { it.installed }} מתוך ${items.size} כלים. ✓ = יש זרימת אוטומציה גלויה."
    }

    private fun runTermux(cmd: String) {
        if (!TermuxBridge.isInstalled(this)) {
            toast("Termux לא מותקן – התקן אותו מהחנות הפנימית (F-Droid) והרץ את 'הגדרת Termux'")
            return
        }
        if (!TermuxBridge.hasPermission(this)) {
            pendingCommand = cmd
            termuxPerm.launch(TermuxBridge.PERMISSION)
            return
        }
        TermuxBridge.run(this, cmd)?.let { toast(it) } ?: toast("נשלח ל-Termux")
    }

    private fun termuxSetup() {
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("termux setup", TermuxBridge.SETUP_COMMAND))
        AlertDialog.Builder(this)
            .setTitle("הגדרת Termux (פעם אחת)")
            .setMessage("הפקודה הועתקה ללוח:\n\n${TermuxBridge.SETUP_COMMAND}\n\n1. פתח את Termux\n2. לחיצה ארוכה ← Paste ← Enter\n3. אשר גישה לאחסון כשתתבקש\n4. חזור לכאן – מעכשיו Kablon יכול להריץ ffmpeg ופקודות ב-Termux.")
            .setPositiveButton("פתח Termux") { _, _ -> ToolCatalog.byId("termux")?.let { ToolCatalog.open(this, it) } }
            .setNegativeButton("סגור", null)
            .show()
    }

    private fun termuxCommandDialog() {
        val input = EditText(this).apply { hint = "לדוגמה: ffmpeg -version"; setText("ffmpeg -version") }
        AlertDialog.Builder(this)
            .setTitle("הרצת פקודה ב-Termux (גלוי)")
            .setView(input)
            .setPositiveButton("הרץ") { _, _ -> runTermux(input.text.toString()) }
            .setNegativeButton("ביטול", null)
            .show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    inner class ToolAdapter : RecyclerView.Adapter<ToolAdapter.VH>() {
        private var items: List<ToolStatus> = emptyList()

        fun submit(list: List<ToolStatus>) {
            items = list
            @Suppress("NotifyDataSetChanged")
            notifyDataSetChanged()
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val cat: TextView = v.findViewById(R.id.toolCategory)
            val name: TextView = v.findViewById(R.id.toolName)
            val status: TextView = v.findViewById(R.id.toolStatus)
            val desc: TextView = v.findViewById(R.id.toolDesc)
            val open: Button = v.findViewById(R.id.toolOpen)
            val install: Button = v.findViewById(R.id.toolInstall)
            val extra: View = v.findViewById(R.id.toolExtra)
            val extra1: Button = v.findViewById(R.id.toolExtra1)
            val extra2: Button = v.findViewById(R.id.toolExtra2)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_tool, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, position: Int) {
            val s = items[position]
            val t = s.tool
            h.cat.text = t.category.title + if (t.automatable) "  •  ✓ אוטומציה" else ""
            h.name.text = t.name
            h.status.text = if (s.installed) "🟢 מותקן ${s.version ?: ""}" else "⚪ לא מותקן"
            h.desc.text = t.description
            h.open.isEnabled = s.installed
            h.open.setOnClickListener { if (!ToolCatalog.open(this@StoreActivity, t)) toast("לא ניתן לפתוח") }
            h.install.text = if (s.installed) "פרטים / עדכון" else "התקן"
            h.install.setOnClickListener { ToolCatalog.install(this@StoreActivity, t) }
            if (t.id == "termux") {
                h.extra.visibility = View.VISIBLE
                h.extra1.text = "הגדרת Termux"
                h.extra2.text = "הרץ פקודה"
                h.extra1.setOnClickListener { termuxSetup() }
                h.extra2.isEnabled = s.installed
                h.extra2.setOnClickListener { termuxCommandDialog() }
            } else {
                h.extra.visibility = View.GONE
            }
        }
    }
}
