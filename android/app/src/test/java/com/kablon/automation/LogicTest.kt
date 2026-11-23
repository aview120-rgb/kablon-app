package com.kablon.automation

import com.kablon.automation.core.StorageBudget
import com.kablon.automation.media.AiApiClient
import com.kablon.automation.store.TermuxBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogicTest {
    @Test
    fun parseSuggestion_withSurroundingText() {
        val ans = "בטח! הנה:\n{\"sound\":\"summer vibes\",\"caption\":\"קיץ בים 🌊 #קיץ #ים\"}\nבהצלחה"
        val p = AiApiClient.parseSuggestion(ans)!!
        assertEquals("summer vibes", p.first)
        assertEquals("קיץ בים 🌊 #קיץ #ים", p.second)
    }

    @Test
    fun parseSuggestion_invalid() {
        assertNull(AiApiClient.parseSuggestion("no json here"))
        assertNull(AiApiClient.parseSuggestion("{broken"))
    }

    @Test
    fun budget_is80GbNot100() {
        assertEquals(80_000_000_000L, StorageBudget.BUDGET_BYTES)
    }

    @Test
    fun snapshot_usableNowRespectsReserve() {
        // מכשיר 128GB (מוצג ~119GB), 15GB פנויים, Kablon משתמש ב-20GB
        val s = StorageBudget.Snapshot(119_000_000_000L, 15_000_000_000L, 20_000_000_000L)
        assertEquals(60_000_000_000L, s.budgetLeft)
        // רק 5GB זמינים בפועל, כי 10GB נשמרים תמיד למערכת
        assertEquals(5_000_000_000L, s.usableNow)
        assertEquals(25, s.budgetPct)
        assertFalse(s.budgetExceedsDevice)
    }

    @Test
    fun snapshot_smallPartitionFlagged() {
        val s = StorageBudget.Snapshot(64_000_000_000L, 30_000_000_000L, 0)
        assertTrue(s.budgetExceedsDevice)
    }

    @Test
    fun format_units() {
        assertEquals("80.0GB", StorageBudget.format(80_000_000_000L))
        assertEquals("1.5MB", StorageBudget.format(1_500_000L))
    }

    @Test
    fun delogoCommand_containsRegion() {
        val c = TermuxBridge.delogoCommand("/sdcard/a.mp4", "/sdcard/a_nowm.mp4", 10, 20, 300, 100)
        assertTrue(c.contains("delogo=x=10:y=20:w=300:h=100"))
        assertTrue(c.startsWith("ffmpeg -y -i '/sdcard/a.mp4'"))
    }
}
