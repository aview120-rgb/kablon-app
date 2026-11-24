package com.dealfinder.yad2;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Keeps the listings seen so far, merging a card with its full page when both were seen. */
public final class ListingStore {

    public static final class Item {
        public String text;      // all lines joined with '\n'
        public boolean detail;   // came from a full listing page
        public String source;    // "Yad2" / "Facebook"
        public long seenAt;
        public ListingParser.Specs specs;

        String key() { return specs.price + "|" + norm(specs.title); }
    }

    private static final String PREFS = "deals";
    private static final int MAX_ITEMS = 1500;
    private static ListingStore instance;

    private final SharedPreferences prefs;
    private final List<Item> items = new ArrayList<>();
    private final List<Runnable> listeners = new ArrayList<>();

    public static synchronized ListingStore get(Context c) {
        if (instance == null) instance = new ListingStore(c.getApplicationContext());
        return instance;
    }

    private ListingStore(Context c) {
        prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        load();
    }

    public synchronized List<Item> all() { return new ArrayList<>(items); }

    public synchronized void addListener(Runnable r) { listeners.add(r); }
    public synchronized void removeListener(Runnable r) { listeners.remove(r); }

    /** @return the stored item if it is new or got richer, otherwise null. */
    public synchronized Item add(List<String> lines, boolean detail, String source) {
        ListingParser.Specs s = ListingParser.analyze(lines, detail);
        if (s.price <= 0 || s.title.isEmpty()) return null;
        String text = String.join("\n", lines);

        Item match = findMatch(s);
        if (match != null) {
            boolean richer = (detail && !match.detail) || (!match.specs.specsKnown() && s.specsKnown());
            if (!richer) return null;
            // Keep the card's city if the page did not show one.
            String merged = match.text + "\n" + text;
            match.text = merged;
            match.detail = match.detail || detail;
            ListingParser.Specs ms = ListingParser.analyze(splitLines(merged), false);
            ms.price = detail ? s.price : match.specs.price;
            ListingParser.score(ms);
            if (ms.title.length() < match.specs.title.length()) ms.title = match.specs.title;
            match.specs = ms;
            match.seenAt = System.currentTimeMillis();
            save();
            notifyListeners();
            return match;
        }

        Item it = new Item();
        it.text = text; it.detail = detail; it.source = source; it.seenAt = System.currentTimeMillis(); it.specs = s;
        items.add(0, it);
        while (items.size() > MAX_ITEMS) items.remove(items.size() - 1);
        save();
        notifyListeners();
        return it;
    }

    private Item findMatch(ListingParser.Specs s) {
        String t = norm(s.title);
        for (Item it : items) {
            if (it.specs.price != s.price) continue;
            String o = norm(it.specs.title);
            if (o.equals(t)) return it;
            int n = Math.min(14, Math.min(o.length(), t.length()));
            if (n >= 8 && (o.contains(t.substring(0, n)) || t.contains(o.substring(0, n)))) return it;
        }
        return null;
    }

    public synchronized void clear() { items.clear(); save(); notifyListeners(); }

    private void notifyListeners() { for (Runnable r : new ArrayList<>(listeners)) r.run(); }

    static String norm(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Punct}…]+", "");
    }

    static List<String> splitLines(String text) {
        List<String> out = new ArrayList<>();
        for (String l : text.split("\n")) if (!l.trim().isEmpty()) out.add(l);
        return out;
    }

    private void load() {
        try {
            JSONArray arr = new JSONArray(prefs.getString("items_v2", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Item it = new Item();
                it.text = o.getString("t");
                it.detail = o.optBoolean("d");
                it.source = o.optString("s", "Yad2");
                it.seenAt = o.optLong("at");
                it.specs = ListingParser.analyze(splitLines(it.text), it.detail);
                if (o.has("p")) { it.specs.price = o.getInt("p"); ListingParser.score(it.specs); }
                items.add(it);
            }
        } catch (Exception ignored) { }
    }

    private void save() {
        try {
            JSONArray arr = new JSONArray();
            for (Item it : items) {
                JSONObject o = new JSONObject();
                o.put("t", it.text); o.put("d", it.detail); o.put("s", it.source); o.put("at", it.seenAt); o.put("p", it.specs.price);
                arr.put(o);
            }
            prefs.edit().putString("items_v2", arr.toString()).apply();
        } catch (Exception ignored) { }
    }
}
