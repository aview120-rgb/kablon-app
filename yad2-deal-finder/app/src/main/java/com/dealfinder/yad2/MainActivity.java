package com.dealfinder.yad2;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class MainActivity extends Activity {

    private static final int BG = Color.parseColor("#F4F5F7");
    private static final int CARD = Color.WHITE;
    private static final int ACCENT = Color.parseColor("#FF5A1F");
    private static final int GOOD = Color.parseColor("#1E8E3E");
    private static final int BAD = Color.parseColor("#C5221F");
    private static final int MUTED = Color.parseColor("#6B7280");

    private SharedPreferences prefs;
    private LinearLayout list;
    private TextView status;
    private TextView summary;
    private String sort = "deal";
    private final Runnable onChange = () -> runOnUiThread(this::render);

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        sort = prefs.getString("sort", "deal");

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setPadding(dp(16), dp(20), dp(16), dp(24));
        scroll.addView(root);

        TextView title = text("🔎 צייד מציאות – מחשבים ניידים", 22, Color.BLACK, true);
        root.addView(title);
        TextView sub = text("פתח את יד2, גלול ברשימת המחשבים הניידים, והאפליקציה תדרג לך כל מודעה שעברת עליה. מודעה בלי מפרט? היכנס אליה פעם אחת והיא תזוהה.", 14, MUTED, false);
        sub.setPadding(0, dp(4), 0, dp(12));
        root.addView(sub);

        status = text("", 15, Color.BLACK, true);
        status.setPadding(dp(12), dp(10), dp(12), dp(10));
        root.addView(status);

        LinearLayout row1 = row();
        row1.addView(button("1. הפעל סורק", v -> openAccessibility()), weight());
        row1.addView(button("2. פתח יד2", v -> openYad2(null)), weight());
        root.addView(row1);

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }

        // Filters
        LinearLayout filters = card();
        TextView ft = text("סינון", 16, Color.BLACK, true);
        filters.addView(ft);
        LinearLayout priceRow = row();
        priceRow.addView(text("מחיר מקסימלי ₪ ", 14, Color.BLACK, false));
        EditText max = new EditText(this);
        max.setInputType(InputType.TYPE_CLASS_NUMBER);
        max.setText(String.valueOf(prefs.getInt("maxPrice", 2000)));
        max.setMinWidth(dp(100));
        max.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int c, int d) { }
            public void onTextChanged(CharSequence s, int a, int c, int d) { }
            public void afterTextChanged(Editable s) {
                try { prefs.edit().putInt("maxPrice", Integer.parseInt(s.toString())).apply(); render(); } catch (NumberFormatException ignored) { }
            }
        });
        priceRow.addView(max);
        filters.addView(priceRow);
        filters.addView(check("רק אזור ירושלים (כולל מבשרת, בית שמש, מעלה אדומים...)", "jlmOnly", false));
        filters.addView(check("רק 16GB RAM ומעלה", "ram16", false));
        filters.addView(check("הצג גם מודעות בלי מפרט ידוע", "showUnknown", true));
        filters.addView(check("סרוק גם את Facebook Marketplace", "facebook", true));
        root.addView(filters);

        LinearLayout sortRow = row();
        sortRow.addView(button("הכי משתלם", v -> setSort("deal")), weight());
        sortRow.addView(button("הכי זול", v -> setSort("price")), weight());
        sortRow.addView(button("הכי חזק", v -> setSort("power")), weight());
        root.addView(sortRow);

        summary = text("", 14, MUTED, false);
        summary.setPadding(0, dp(8), 0, dp(4));
        root.addView(summary);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list);

        Button clear = button("נקה רשימה", v -> new AlertDialog.Builder(this)
                .setMessage("למחוק את כל המודעות שנאספו?")
                .setPositiveButton("מחק", (d, w) -> ListingStore.get(this).clear())
                .setNegativeButton("ביטול", null).show());
        clear.setBackgroundColor(Color.parseColor("#E5E7EB"));
        clear.setTextColor(Color.BLACK);
        root.addView(clear);

        setContentView(scroll);
    }

    @Override protected void onResume() {
        super.onResume();
        ListingStore.get(this).addListener(onChange);
        render();
    }

    @Override protected void onPause() {
        super.onPause();
        ListingStore.get(this).removeListener(onChange);
    }

    private void setSort(String s) { sort = s; prefs.edit().putString("sort", s).apply(); render(); }

    private void render() {
        boolean on = isServiceOn();
        String diag = "";
        if (on) {
            String y = findYad2Package();
            diag = "\nיד2 במכשיר: " + (y != null ? "נמצאה ✓" : "לא נמצאה ✗")
                    + " · סריקות: " + DealScannerService.scans
                    + " · מודעות בסריקה האחרונה: " + DealScannerService.cardsLastScan;
        }
        status.setText((on ? "✅ הסורק פעיל – עבור ליד2 וגלול" : "⛔ הסורק כבוי – לחץ \"הפעל סורק\"") + diag);
        status.setBackground(round(on ? Color.parseColor("#E6F4EA") : Color.parseColor("#FCE8E6"), 10));

        int maxPrice = prefs.getInt("maxPrice", 2000);
        boolean jlm = prefs.getBoolean("jlmOnly", false);
        boolean ram16 = prefs.getBoolean("ram16", false);
        boolean unknown = prefs.getBoolean("showUnknown", true);

        List<ListingStore.Item> all = ListingStore.get(this).all();
        List<ListingStore.Item> shown = new ArrayList<>();
        int hidden = 0;
        for (ListingStore.Item it : all) {
            ListingParser.Specs s = it.specs;
            boolean ok = !s.partsOrAccessory && s.price <= maxPrice
                    && (!jlm || s.jerusalemArea)
                    && (!ram16 || s.ramGb >= 16)
                    && (unknown || s.specsKnown());
            if (ok) shown.add(it); else hidden++;
        }
        Comparator<ListingStore.Item> cmp;
        switch (sort) {
            case "price": cmp = Comparator.comparingInt(i -> i.specs.price); break;
            case "power": cmp = Comparator.comparingInt((ListingStore.Item i) -> -i.specs.points).thenComparingInt(i -> i.specs.price); break;
            default: cmp = Comparator.comparingInt((ListingStore.Item i) -> i.specs.specsKnown() ? -i.specs.dealPercent : 1000).thenComparingInt(i -> i.specs.price);
        }
        shown.sort(cmp);

        String sortName = sort.equals("price") ? "הכי זול" : sort.equals("power") ? "הכי חזק" : "הכי משתלם";
        summary.setText("נאספו " + all.size() + " מודעות · מוצגות " + shown.size() + " · מוסתרות " + hidden + " · מיון: " + sortName);

        list.removeAllViews();
        if (shown.isEmpty()) {
            TextView empty = text("עוד אין מודעות. הפעל את הסורק, פתח את יד2 ← מחשבים ניידים, וגלול למטה.", 15, MUTED, false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(8), dp(32), dp(8), dp(32));
            list.addView(empty);
            return;
        }
        int rank = 1;
        for (ListingStore.Item it : shown) {
            list.addView(itemView(it, rank++));
            if (rank > 300) break;
        }
    }

    private View itemView(ListingStore.Item it, int rank) {
        ListingParser.Specs s = it.specs;
        LinearLayout c = card();

        LinearLayout top = row();
        TextView price = text("#" + rank + "  ₪" + String.format("%,d", s.price), 20, Color.BLACK, true);
        top.addView(price, weight());
        String badge; int color;
        if (!s.specsKnown()) { badge = "❔ חסר מפרט"; color = MUTED; }
        else if (s.dealPercent >= 25) { badge = "🔥 מציאה " + s.dealPercent + "%"; color = GOOD; }
        else if (s.dealPercent >= 8) { badge = "👍 טוב " + s.dealPercent + "%"; color = GOOD; }
        else if (s.dealPercent >= -10) { badge = "➖ מחיר שוק"; color = MUTED; }
        else { badge = "👎 יקר " + (-s.dealPercent) + "%"; color = BAD; }
        TextView b = text(badge, 14, Color.WHITE, true);
        b.setPadding(dp(10), dp(4), dp(10), dp(4));
        b.setBackground(round(color, 20));
        top.addView(b);
        c.addView(top);

        c.addView(text(s.title, 15, Color.BLACK, false));

        StringBuilder spec = new StringBuilder();
        if (!s.cpuLabel.isEmpty()) spec.append("⚙️ ").append(s.cpuLabel).append("   ");
        if (s.ramGb > 0) spec.append("🧠 ").append(s.ramGb).append("GB   ");
        if (s.ssdGb > 0) spec.append("💾 ").append(s.ssdGb >= 1000 ? (s.ssdGb / 1000) + "TB" : s.ssdGb + "GB");
        if (spec.length() == 0) spec.append("היכנס למודעה ביד2 כדי שהמפרט יזוהה");
        TextView sp = text(spec.toString(), 14, Color.parseColor("#111827"), false);
        sp.setPadding(0, dp(4), 0, 0);
        c.addView(sp);

        String meta = "📍 " + (s.city.isEmpty() ? "לא ידוע" : s.city) + (s.jerusalemArea ? " ⭐" : "") + "   · " + it.source
                + (s.fairPrice > 0 ? "   · שווי מוערך ₪" + String.format("%,d", s.fairPrice) : "");
        c.addView(text(meta, 13, MUTED, false));

        TextView full = text(it.text, 13, Color.DKGRAY, false);
        full.setVisibility(View.GONE);
        full.setPadding(0, dp(6), 0, 0);
        c.addView(full);

        LinearLayout actions = row();
        Button find = button("חפש ביד2", v -> openYad2(s.title));
        Button more = button("פרטים", v -> full.setVisibility(full.getVisibility() == View.GONE ? View.VISIBLE : View.GONE));
        more.setBackgroundColor(Color.parseColor("#E5E7EB")); more.setTextColor(Color.BLACK);
        actions.addView(find, weight());
        actions.addView(more, weight());
        c.addView(actions);
        return c;
    }

    // ---------- actions ----------

    private boolean isServiceOn() {
        String enabled = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return enabled != null && enabled.contains(getPackageName() + "/");
    }

    private void openAccessibility() {
        new AlertDialog.Builder(this)
                .setTitle("הפעלת הסורק")
                .setMessage("במסך שייפתח: \"אפליקציות מותקנות\" ← \"צייד מציאות\" ← הפעל.\n\n"
                        + "אם האפשרות אפורה (\"הגדרה מוגבלת\"): היכנס להגדרות ← אפליקציות ← צייד מציאות ← ⋮ למעלה ← \"אפשר הגדרות מוגבלות\", ואז נסה שוב.\n\n"
                        + "הסורק רק קורא את המודעות שמופיעות על המסך ביד2/פייסבוק. הוא לא לוחץ, לא גולל ולא שולח שום מידע מהטלפון.")
                .setPositiveButton("פתח הגדרות", (d, w) -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)))
                .setNeutralButton("הגדרות האפליקציה", (d, w) -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))))
                .show();
    }

    private void openYad2(String query) {
        if (query != null) {
            ClipboardManager cm = getSystemService(ClipboardManager.class);
            cm.setPrimaryClip(ClipData.newPlainText("search", query));
            Toast.makeText(this, "הכותרת הועתקה – הדבק בחיפוש של יד2", Toast.LENGTH_LONG).show();
        }
        String yad2 = findYad2Package();
        if (yad2 != null) {
            Intent i = getPackageManager().getLaunchIntentForPackage(yad2);
            if (i != null) { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i); return; }
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.yad2.co.il/products/computers-and-accessories?category=6&item=34")));
        } catch (Exception e) {
            Toast.makeText(this, "לא נמצאה אפליקציית יד2 – פתח אותה ידנית", Toast.LENGTH_LONG).show();
        }
    }

    /** The installed Yad2 app (any launcher app whose package mentions yad2, except this app). */
    private String findYad2Package() {
        String last = DealScannerService.lastYad2Package;
        if (last != null && !last.equals(getPackageName())) return last;
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (android.content.pm.ResolveInfo ri : getPackageManager().queryIntentActivities(main, 0)) {
            String p = ri.activityInfo.packageName;
            if (!p.equals(getPackageName()) && p.toLowerCase(java.util.Locale.ROOT).contains("yad2")) return p;
        }
        return null;
    }

    // ---------- tiny UI helpers ----------

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(sp); t.setTextColor(color);
        t.setTextDirection(View.TEXT_DIRECTION_ANY_RTL);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private Button button(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s); b.setAllCaps(false); b.setTextColor(Color.WHITE); b.setTextSize(14);
        b.setBackground(round(ACCENT, 12));
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(6), 0, dp(6));
        return r;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        p.setMargins(dp(4), 0, dp(4), 0);
        return p;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(14), dp(12), dp(14), dp(12));
        c.setBackground(round(CARD, 14));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, dp(6), 0, dp(6));
        c.setLayoutParams(p);
        return c;
    }

    private CheckBox check(String label, String key, boolean def) {
        CheckBox cb = new CheckBox(this);
        cb.setText(label);
        cb.setChecked(prefs.getBoolean(key, def));
        cb.setOnCheckedChangeListener((v, on) -> { prefs.edit().putBoolean(key, on).apply(); render(); });
        return cb;
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color); g.setCornerRadius(dp(radiusDp));
        return g;
    }
}
