package com.autoagent.app.sync

import com.autoagent.app.data.RemoteState
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SyncProtocolTest {
    @Test
    fun encodesEverythingCollectedOffline() {
        val json = JSONObject(
            SyncProtocol.encode(
                SyncRequest(
                    deviceId = "android-1", deviceName = "הטלפון שלי", appVersion = "1.3.0", now = 1000,
                    status = mapOf("battery" to 55, "charging" to true, "network" to "wifi", "missing" to null),
                    received = listOf("t1", "t2"),
                    results = listOf(TaskResult("t0", "done", "נשלח ב-WhatsApp", 900)),
                    uploads = listOf(
                        Upload("u1", "inbox", """{"sender":"אמא","text":"מתי מגיעים?"}""", 800),
                        Upload("u2", "log", "not json", 801),
                    ),
                ),
            ),
        )
        assertEquals("android-1", json.getString("deviceId"))
        assertEquals("הטלפון שלי", json.getString("deviceName"))
        assertEquals(55, json.getJSONObject("status").getInt("battery"))
        assertTrue(json.getJSONObject("status").isNull("missing"))
        assertEquals(listOf("t1", "t2"), (0 until 2).map { json.getJSONArray("received").getString(it) })
        assertEquals("נשלח ב-WhatsApp", json.getJSONArray("results").getJSONObject(0).getString("note"))
        val uploads = json.getJSONArray("uploads")
        assertEquals("אמא", uploads.getJSONObject(0).getJSONObject("data").getString("sender"))
        assertEquals("not json", uploads.getJSONObject(1).getString("data"))
    }

    @Test
    fun decodesServerReply() {
        val r = SyncProtocol.decode(
            """{"serverTime":5000,"tasks":[{"id":"t_1","type":"send_message","title":"שליחת הודעה","runAt":7000,
               "result":{"text":"בוקר טוב","autoSend":true,"executeAt":7000},"occurrence":2}],
               "cancelled":["t_9"],"acceptedUploads":["u1"],"acceptedResults":["t0"],"hasMore":true,"nextPollMs":25000}""",
        )
        assertEquals(5000, r.serverTime)
        assertEquals(1, r.tasks.size)
        assertEquals("send_message", r.tasks[0].type)
        assertEquals(2, r.tasks[0].occurrence)
        assertEquals("בוקר טוב", JSONObject(r.tasks[0].resultJson).getString("text"))
        assertEquals(listOf("t_9"), r.cancelled)
        assertEquals(listOf("u1"), r.acceptedUploads)
        assertEquals(listOf("t0"), r.acceptedResults)
        assertTrue(r.hasMore)
    }

    @Test
    fun toleratesMinimalReplyAndErrors() {
        val r = SyncProtocol.decode("{}")
        assertTrue(r.tasks.isEmpty())
        assertFalse(r.hasMore)
        assertEquals("אסימון גישה שגוי או חסר", SyncProtocol.errorMessage("""{"error":"אסימון גישה שגוי או חסר"}"""))
        assertNull(SyncProtocol.errorMessage("<html>"))
        assertNull(SyncProtocol.errorMessage(null))
    }

    @Test
    fun mapsLocalOutcomes() {
        assertEquals("done", SyncProtocol.remoteStatus(RemoteState.DONE))
        assertEquals("expired", SyncProtocol.remoteStatus(RemoteState.EXPIRED))
        assertEquals("failed", SyncProtocol.remoteStatus(RemoteState.FAILED))
    }

    @Test
    fun plannerCorrectsClockAndDecides() {
        // Phone clock is 30 s ahead of the server.
        assertEquals(130_000, RemotePlanner.toLocalTime(serverRunAt = 100_000, serverNow = 50_000, localNow = 80_000))
        assertEquals(100_000, RemotePlanner.toLocalTime(100_000, 0, 80_000))
        val now = 10 * 60_000L
        assertEquals(RemotePlanner.Decision.SCHEDULE, RemotePlanner.decide(now + 60_000, now, 15))
        assertEquals(RemotePlanner.Decision.RUN_NOW, RemotePlanner.decide(now - 5 * 60_000, now, 15))
        assertEquals(RemotePlanner.Decision.RUN_NOW, RemotePlanner.decide(now, now, 0))
        assertEquals(RemotePlanner.Decision.EXPIRE, RemotePlanner.decide(now - 16 * 60_000, now, 15))
    }

    @Test
    fun backoffGrowsIsCappedAndResets() {
        val b = Backoff(baseMs = 1_000, maxMs = 30_000, jitter = 0.0, random = Random(1))
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L), List(7) { b.next() })
        b.reset()
        assertEquals(1_000L, b.next())
        val j = Backoff(baseMs = 1_000, maxMs = 60_000, jitter = 0.2, random = Random(7))
        repeat(20) { val v = j.next(); assertTrue(v in 500..60_000) }
    }
}
