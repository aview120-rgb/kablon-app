package com.dealfinder.yad2;

import android.accessibilityservice.AccessibilityService;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads the listings that are on screen while the user scrolls in Yad2 (or Facebook Marketplace).
 * It only reads what the user is already looking at; it never taps, scrolls or sends anything.
 */
public class DealScannerService extends AccessibilityService {

    static final String CHANNEL = "deals";
    static volatile boolean running;
    static volatile String lastYad2Package;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private String pendingPackage;
    private final Runnable scan = this::scanNow;
    private int notifId = 100;

    private static final String[] DETAIL_CUTOFF = {"מוצרים נוספים", "מומלץ עבורך", "מודעות דומות", "מודעות נוספות", "Similar items", "Today's picks"};

    @Override protected void onServiceConnected() {
        running = true;
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "מציאות", NotificationManager.IMPORTANCE_HIGH));
    }

    @Override public boolean onUnbind(Intent intent) { running = false; return super.onUnbind(intent); }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        CharSequence pkg = event.getPackageName();
        if (pkg == null) return;
        String p = pkg.toString();
        if (!isWatched(p)) return;
        if (p.contains("yad2")) lastYad2Package = p;
        pendingPackage = p;
        // Debounce: scan once the screen settles.
        handler.removeCallbacks(scan);
        handler.postDelayed(scan, 350);
    }

    private boolean isWatched(String p) {
        if (p.toLowerCase(Locale.ROOT).contains("yad2")) return true;
        SharedPreferences prefs = getSharedPreferences("settings", MODE_PRIVATE);
        return prefs.getBoolean("facebook", true) && p.equals("com.facebook.katana");
    }

    @Override public void onInterrupt() { }

    private void scanNow() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        String source = pendingPackage != null && pendingPackage.contains("facebook") ? "Facebook" : "Yad2";

        List<List<String>> cards = new ArrayList<>();
        List<String> detailLines = null;

        List<String> screen = new ArrayList<>();
        collectText(root, screen, 0);
        String screenText = String.join("\n", screen);
        boolean isDetail = screenText.contains("על המוצר") || screenText.contains("פרטים נוספים")
                || screenText.contains("תיאור") || screenText.contains("הצגת מספר טלפון") || screenText.contains("Seller's description");

        if (isDetail) {
            detailLines = new ArrayList<>();
            for (String l : screen) {
                boolean stop = false;
                for (String c : DETAIL_CUTOFF) if (l.contains(c)) { stop = true; break; }
                if (stop) break;
                detailLines.add(l);
            }
        } else {
            findCards(root, cards, 0);
        }

        ListingStore store = ListingStore.get(this);
        if (detailLines != null && !ListingParser.prices(String.join("\n", detailLines)).isEmpty()) {
            maybeNotify(store.add(detailLines, true, source));
        }
        for (List<String> c : cards) maybeNotify(store.add(c, false, source));
    }

    /** Cards are the children of scrollable lists/grids that contain a price. */
    private void findCards(AccessibilityNodeInfo node, List<List<String>> out, int depth) {
        if (node == null || depth > 40) return;
        if (node.isScrollable()) {
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child == null) continue;
                List<String> lines = new ArrayList<>();
                collectText(child, lines, 0);
                String joined = String.join("\n", lines);
                int priceCount = ListingParser.prices(joined).size();
                if (priceCount >= 1 && priceCount <= 3 && joined.length() < 600) out.add(lines);
                else if (priceCount > 3) findCards(child, out, depth + 1); // nested list
            }
            return;
        }
        for (int i = 0; i < node.getChildCount(); i++) findCards(node.getChild(i), out, depth + 1);
    }

    private static void collectText(AccessibilityNodeInfo n, List<String> out, int depth) {
        if (n == null || depth > 60) return;
        CharSequence t = n.getText();
        CharSequence d = n.getContentDescription();
        if (t != null && t.toString().trim().length() > 0) out.add(t.toString().trim());
        else if (d != null && d.toString().trim().length() > 1) out.add(d.toString().trim());
        for (int i = 0; i < n.getChildCount(); i++) collectText(n.getChild(i), out, depth + 1);
    }

    private void maybeNotify(ListingStore.Item it) {
        if (it == null || it.specs.partsOrAccessory || !it.specs.specsKnown()) return;
        SharedPreferences prefs = getSharedPreferences("settings", MODE_PRIVATE);
        int maxPrice = prefs.getInt("maxPrice", 2000);
        if (it.specs.price > maxPrice || it.specs.dealPercent < 20 || it.specs.cpuScore < 50) return;
        if (prefs.getBoolean("jlmOnly", false) && !it.specs.jerusalemArea) return;

        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.star_big_on)
                .setContentTitle("🔥 מציאה: ₪" + it.specs.price + " (" + it.specs.dealPercent + "% מתחת לשוק)")
                .setContentText(it.specs.cpuLabel + " · " + (it.specs.ramGb > 0 ? it.specs.ramGb + "GB · " : "") + it.specs.title)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        try { nm.notify(notifId++, n); } catch (SecurityException ignored) { }
    }
}
