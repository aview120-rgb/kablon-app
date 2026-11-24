package com.dealfinder.yad2;

import static org.junit.Assert.*;

import java.util.Arrays;
import org.junit.Test;

public class ListingParserTest {

    private static ListingParser.Specs card(String... lines) { return ListingParser.analyze(Arrays.asList(lines), false); }
    private static ListingParser.Specs detail(String... lines) { return ListingParser.analyze(Arrays.asList(lines), true); }

    @Test public void thinkpadL13DetailPage() {
        ListingParser.Specs s = detail("כמו חדש", "₪ 1,500", "ThinkPad עיסקי קליל I7", "בני ברק", "אבי", "על המוצר",
                "ThinkPad עיסקי קליל I7 Lenovo ThinkPad L13. Lenovo ThinkPad L13 Gen 2 Intel Core i7-1165G7 13.3\" FHD IPS 16GB SSD 512 Windows 11. 20 יחידות במלאי");
        assertEquals(1500, s.price);
        assertEquals("i7-1165G7", s.cpuLabel);
        assertEquals(16, s.ramGb);
        assertEquals(512, s.ssdGb);
        assertTrue(s.specsKnown());
        assertFalse(s.jerusalemArea);
    }

    @Test public void x13CardWithOldPrice() {
        ListingParser.Specs s = card("ישראל", "₪1,300", "₪1,450", "ירושלים", "ממשרדהייטק שנסגר Thinkpad X13", "כמו חדש", "בהזדמנות");
        assertEquals(1300, s.price);
        assertTrue(s.jerusalemArea);
        assertFalse(s.specsKnown());
    }

    @Test public void x13DetailI5Gen10() {
        ListingParser.Specs s = detail("₪ 1,300", "ממשרדהייטק שנסגר Thinkpad X13", "ירושלים",
                "מעבד Intel Core מהדור ה-10 i5-10210U, זיכרון (RAM): 16GB. מחשב נייד, יצרן: Lenovo. 256GB SSD NVMe");
        assertEquals("i5-10210U", s.cpuLabel);
        assertEquals(40, s.cpuScore);
        assertEquals(16, s.ramGb);
        assertEquals(256, s.ssdGb);
    }

    @Test public void hebrewGenerationAndSlash() {
        ListingParser.Specs s = card("₪1,350", "בני ברק", "i7 דור 12 סוללה מעולה 16/512");
        assertEquals(80, s.cpuScore);
        assertEquals(16, s.ramGb);
        assertEquals(512, s.ssdGb);
        assertTrue(s.dealPercent > 0);
    }

    @Test public void sonyVaioIsBadDeal() {
        ListingParser.Specs s = detail("₪ 1,850", "₪ 2,200", "מחשב נייד מגע sony vaio pro", "באר שבע",
                "עם מעבד intel i5 וזכרון מהיר SSD 256 ו8 גיגה ראם . עם מסך מגע מדהים", "i5-4200U");
        assertEquals(1850, s.price);
        assertEquals(8, s.ramGb);
        assertTrue(s.dealPercent < -100);
    }

    @Test public void accessoryFiltered() {
        assertTrue(card("₪80", "ירושלים", "דיסק SSD 240GB Kingston").partsOrAccessory);
        assertTrue(card("₪350", "קריית אונו", "זיכרון למחשב נייד 8GB").partsOrAccessory);
    }

    @Test public void ryzenAndCeleron() {
        assertEquals(60, card("₪1,200", "ThinkPad X13 Ryzen 5 PRO 4650U 16GB").cpuScore);
        assertEquals(5, card("₪600", "Lenovo V14 Celeron N4020 4GB").cpuScore);
    }

    @Test public void barePriceFormats() {
        assertEquals(Arrays.asList(1300), ListingParser.prices("1,300 ₪"));
        assertEquals(Arrays.asList(990), ListingParser.prices("990 ש\"ח"));
    }
}
