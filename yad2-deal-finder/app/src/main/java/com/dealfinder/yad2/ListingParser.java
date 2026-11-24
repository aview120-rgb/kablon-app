package com.dealfinder.yad2;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure-Java analysis of a listing's text: price, CPU, RAM, SSD, area and a deal score. */
public final class ListingParser {

    public static final class Specs {
        public int price = -1;
        public String title = "";
        public String cpuLabel = "";
        public int cpuScore = -1;      // -1 = unknown
        public int ramGb = -1;
        public int ssdGb = -1;
        public boolean jerusalemArea;
        public String city = "";
        public boolean partsOrAccessory;
        public int points;             // overall hardware value
        public int fairPrice;          // estimated market price in NIS
        public int dealPercent;        // how much cheaper than fair (negative = expensive)

        public boolean specsKnown() { return cpuScore >= 0; }
    }

    private static final Pattern PRICE = Pattern.compile(
            "(?:₪\\s*([0-9][0-9,]{1,8}))|(?:([0-9][0-9,]{1,8})\\s*(?:₪|ש\"ח|ש״ח|שח\\b))");

    private static final Pattern INTEL = Pattern.compile(
            "\\bi([3579])[\\s-]*(\\d{4,5})([a-z]{0,2}\\d?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern INTEL_BARE = Pattern.compile(
            "\\b(1[0-4]\\d{2,3})(g[1-7]|u|p|h|hs|hx)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern GEN_HE = Pattern.compile("דור\\s*-?\\s*(\\d{1,2})");
    private static final Pattern GEN_EN = Pattern.compile("(\\d{1,2})(?:th|st|nd|rd)\\s*gen", Pattern.CASE_INSENSITIVE);
    private static final Pattern I_TIER = Pattern.compile("\\bi([3579])\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern ULTRA = Pattern.compile("ultra\\s*([579])", Pattern.CASE_INSENSITIVE);
    private static final Pattern RYZEN = Pattern.compile(
            "ryzen\\s*([3579])?\\s*(?:pro\\s*)?(\\d{4})", Pattern.CASE_INSENSITIVE);
    private static final Pattern APPLE_M = Pattern.compile("\\bm([1-4])\\b(?:\\s*(pro|max))?", Pattern.CASE_INSENSITIVE);
    private static final Pattern WEAK_CPU = Pattern.compile(
            "celeron|pentium|atom|athlon|\\bn[0-9]{3,4}\\b|core\\s*2|amd\\s*a[0-9]", Pattern.CASE_INSENSITIVE);

    private static final Pattern RAM_SLASH = Pattern.compile("\\b(4|8|12|16|20|24|32|64)\\s*(?:gb)?\\s*/\\s*(\\d{3,4}|[12]\\s*tb)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SIZE = Pattern.compile(
            "(\\d{1,4})\\s*(gb|g\\b|tb|ג'?יגה|ג׳יגה|גיגה|גיג|טרה)", Pattern.CASE_INSENSITIVE);
    private static final Pattern RAM_WORD_BEFORE = Pattern.compile(
            "(?:ram|ראם|רם|זכרון|זיכרון)\\s*:?\\s*(4|8|12|16|20|24|32|64)\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern SSD_WORD_BEFORE = Pattern.compile(
            "(?:ssd|nvme|hdd|דיסק|אחסון)\\s*:?\\s*(\\d{3,4})(?!\\d)", Pattern.CASE_INSENSITIVE);

    private static final String[] JERUSALEM = {
            "ירושלים", "מבשרת", "בית שמש", "מעלה אדומים", "גבעת זאב", "ביתר", "מודיעין", "jerusalem"
    };
    private static final Pattern PARTS = Pattern.compile(
            "לחלקים|חלקים|שבור|תקול|לא נדלק|מטען|תיק|מקלדת ל|סוללה ל|זיכרון לנייד|זכרון לנייד|מעמד|כונן|דיסק ל|מסך ל|ssd ל|למחשב נייד",
            Pattern.CASE_INSENSITIVE);

    private ListingParser() {}

    /** All prices that appear in a piece of text, in order. */
    public static List<Integer> prices(String text) {
        List<Integer> out = new ArrayList<>();
        Matcher m = PRICE.matcher(text);
        while (m.find()) {
            String g = m.group(1) != null ? m.group(1) : m.group(2);
            try {
                int v = Integer.parseInt(g.replace(",", ""));
                if (v >= 30 && v <= 30000) out.add(v);
            } catch (NumberFormatException ignored) { }
        }
        return out;
    }

    public static boolean isPriceLine(String line) {
        String t = line.trim();
        return !prices(t).isEmpty() && t.replaceAll("[0-9,₪\\sש\"״ח]", "").length() <= 2;
    }

    /**
     * @param lines   the text lines of one listing (card or detail page)
     * @param detail  true when this is a full listing page (first price is the asking price)
     */
    public static Specs analyze(List<String> lines, boolean detail) {
        Specs s = new Specs();
        StringBuilder sb = new StringBuilder();
        for (String l : lines) sb.append(l).append('\n');
        String all = sb.toString();
        String lower = all.toLowerCase(Locale.ROOT);

        // Price: on a card take the lowest (the crossed-out "was" price is higher);
        // on a detail page take the first one.
        List<Integer> ps = prices(all);
        if (!ps.isEmpty()) {
            if (detail) s.price = ps.get(0);
            else { int min = Integer.MAX_VALUE; for (int p : ps) min = Math.min(min, p); s.price = min; }
        }

        // Title: longest reasonable non-price line.
        for (String l : lines) {
            String t = l.trim();
            if (t.length() < 3 || t.length() > 90 || isPriceLine(t)) continue;
            if (!t.matches(".*[\\p{L}].*")) continue;
            if (t.length() > s.title.length()) s.title = t;
        }

        for (String l : lines) {
            String t = l.trim();
            for (String j : JERUSALEM) {
                if (t.toLowerCase(Locale.ROOT).contains(j)) { s.jerusalemArea = true; if (s.city.isEmpty() && t.length() < 25) s.city = t; }
            }
        }
        if (s.city.isEmpty()) s.city = guessCity(lines);

        parseCpu(all, s);
        parseMemory(lower, s);

        s.partsOrAccessory = (s.price > 0 && s.price < 200) || (PARTS.matcher(lower).find() && s.cpuScore < 0);

        score(s);
        return s;
    }

    private static String guessCity(List<String> lines) {
        // Yad2 cards show the city as a short grey line next to the price.
        for (String l : lines) {
            String t = l.trim();
            if (t.length() >= 2 && t.length() <= 14 && t.matches("[\\u0590-\\u05FF\\s\\-'\"׳״]+")
                    && !t.contains("כמו חדש") && !t.contains("הזדמנות") && !t.contains("משומש") && !t.contains("חדש")) {
                return t;
            }
        }
        return "";
    }

    static void parseCpu(String all, Specs s) {
        int gen = -1; int tier = -1; String label = null;

        Matcher m = INTEL.matcher(all);
        if (m.find()) {
            tier = Integer.parseInt(m.group(1));
            String num = m.group(2);
            gen = intelGen(num, m.group(3));
            label = "i" + tier + "-" + num + m.group(3).toUpperCase(Locale.ROOT);
        } else {
            Matcher b = INTEL_BARE.matcher(all);
            if (b.find()) { gen = intelGen(b.group(1), b.group(2)); label = b.group(1) + b.group(2).toUpperCase(Locale.ROOT); }
        }
        if (gen < 0) {
            Matcher g = GEN_HE.matcher(all);
            if (g.find()) gen = Integer.parseInt(g.group(1));
            else { Matcher ge = GEN_EN.matcher(all); if (ge.find()) gen = Integer.parseInt(ge.group(1)); }
            if (gen > 15) gen = -1;
        }
        if (tier < 0) { Matcher t = I_TIER.matcher(all); if (t.find()) tier = Integer.parseInt(t.group(1)); }

        int score = -1;
        Matcher u = ULTRA.matcher(all);
        Matcher r = RYZEN.matcher(all);
        Matcher a = APPLE_M.matcher(all);
        if (u.find()) {
            score = 90; label = "Core Ultra " + u.group(1);
        } else if (gen > 0) {
            score = genScore(gen);
            if (tier == 7 || tier == 9) score += 5;
            if (tier == 3) score -= 15;
            if (label == null) label = (tier > 0 ? "i" + tier + " " : "") + "דור " + gen;
        } else if (r.find()) {
            int series = r.group(2).charAt(0) - '0';
            score = series <= 2 ? 25 : series == 3 ? 38 : series == 4 ? 60 : series == 5 ? 70 : 80;
            if ("3".equals(r.group(1))) score -= 12;
            if ("7".equals(r.group(1)) || "9".equals(r.group(1))) score += 5;
            label = "Ryzen " + (r.group(1) != null ? r.group(1) + " " : "") + r.group(2);
        } else if (a.find() && all.toLowerCase(Locale.ROOT).contains("mac")) {
            score = 75 + 8 * (Integer.parseInt(a.group(1)) - 1);
            label = "Apple M" + a.group(1);
        } else if (WEAK_CPU.matcher(all).find()) {
            score = 5; label = "מעבד חלש (Celeron/Pentium)";
        } else if (tier > 0) {
            // "i5" without generation: unknown age, assume old-ish but flag as unknown.
            label = "i" + tier + " (דור לא ידוע)";
        }
        s.cpuScore = Math.min(score, 100);
        s.cpuLabel = label == null ? "" : label;
    }

    static int intelGen(String num, String suffix) {
        if (num.length() == 5) return Integer.parseInt(num.substring(0, 2));
        if (num.charAt(0) == '1') {
            int two = Integer.parseInt(num.substring(0, 2));
            if (two >= 10 && two <= 14) return two;
        }
        return num.charAt(0) - '0';
    }

    static int genScore(int gen) {
        if (gen <= 4) return 5;
        switch (gen) {
            case 5: return 8;
            case 6: return 12;
            case 7: return 16;
            case 8: return 30;
            case 9: return 32;
            case 10: return 40;
            case 11: return 55;
            case 12: return 75;
            case 13: return 85;
            default: return 90;
        }
    }

    static void parseMemory(String lower, Specs s) {
        Matcher sl = RAM_SLASH.matcher(lower);
        if (sl.find()) {
            s.ramGb = Integer.parseInt(sl.group(1));
            String st = sl.group(2).replace(" ", "");
            s.ssdGb = st.endsWith("tb") ? Integer.parseInt(st.substring(0, 1)) * 1000 : Integer.parseInt(st);
        }
        Matcher rw = RAM_WORD_BEFORE.matcher(lower);
        if (s.ramGb < 0 && rw.find()) s.ramGb = Integer.parseInt(rw.group(1));

        Matcher sw = SSD_WORD_BEFORE.matcher(lower);
        if (s.ssdGb < 0 && sw.find()) s.ssdGb = Integer.parseInt(sw.group(1));

        Matcher m = SIZE.matcher(lower);
        while (m.find()) {
            int v = Integer.parseInt(m.group(1));
            String unit = m.group(2);
            if (unit.equals("tb") || unit.equals("טרה")) { if (s.ssdGb < 0) s.ssdGb = v * 1000; continue; }
            String after = lower.substring(m.end(), Math.min(lower.length(), m.end() + 14));
            String before = lower.substring(Math.max(0, m.start() - 10), m.start());
            boolean storageHint = after.matches("(?s)\\s*(ssd|hdd|nvme|emmc|דיסק|אחסון|כונן).*") && !after.matches("(?s)\\s*(ssd|hdd|nvme|emmc|דיסק|אחסון|כונן)\\s*:?\\s*\\d.*") || before.matches("(?s).*(ssd|nvme|דיסק|אחסון)\\s*:?\\s*$");
            if (v >= 100 || storageHint) {
                if (v >= 100 && s.ssdGb < 0) s.ssdGb = v;
            } else if (s.ramGb < 0 && (v == 4 || v == 8 || v == 12 || v == 16 || v == 20 || v == 24 || v == 32 || v == 64)) {
                s.ramGb = v;
            }
        }
    }

    static void score(Specs s) {
        int pts = Math.max(s.cpuScore, 0);
        if (s.ramGb >= 32) pts += 20; else if (s.ramGb >= 16) pts += 15; else if (s.ramGb >= 12) pts += 8;
        else if (s.ramGb == 8) pts += 0; else if (s.ramGb > 0) pts -= 15;
        if (s.ssdGb >= 1000) pts += 8; else if (s.ssdGb >= 480) pts += 5; else if (s.ssdGb > 0 && s.ssdGb < 200) pts -= 3;
        s.points = Math.max(pts, 0);
        s.fairPrice = s.specsKnown() ? Math.max(250, s.points * 19) : -1;
        if (s.fairPrice > 0 && s.price > 0) s.dealPercent = Math.round(100f * (s.fairPrice - s.price) / s.fairPrice);
    }
}
