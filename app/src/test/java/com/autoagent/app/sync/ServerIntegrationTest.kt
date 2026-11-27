package com.autoagent.app.sync

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/**
 * Talks to a real queue server (server/). Runs only when KABLON_TEST_SERVER and KABLON_TEST_TOKEN are set, e.g.:
 *   (cd server && KABLON_TOKEN=t PORT=8799 DATA_FILE=/tmp/k.json node src/index.js) &
 *   KABLON_TEST_SERVER=http://127.0.0.1:8799 KABLON_TEST_TOKEN=t ./gradlew testDebugUnitTest
 */
class ServerIntegrationTest {
    private val base = System.getenv("KABLON_TEST_SERVER")
    private val token = System.getenv("KABLON_TEST_TOKEN")

    private fun createTask(body: JSONObject): String {
        val c = URL("$base/api/v1/tasks").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Authorization", "Bearer $token")
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        assertEquals(201, c.responseCode)
        return JSONObject(c.inputStream.bufferedReader().readText()).getJSONObject("task").getString("id")
    }

    @Test
    fun offlineRoundTripAgainstRealServer() {
        assumeTrue("set KABLON_TEST_SERVER to run", !base.isNullOrBlank() && !token.isNullOrBlank())
        val device = "it-" + System.nanoTime()
        val api = SyncApi(base!!, token!!)
        assertTrue(api.health().contains("\"ok\":true"))

        // While "offline", the server prepares a message due in one hour.
        val id = createTask(JSONObject().put("type", "send_message").put("deviceId", device).put("runAt", System.currentTimeMillis() + 3_600_000)
            .put("payload", JSONObject().put("channel", "WHATSAPP").put("recipient", "0501234567").put("recipientLabel", "דנה").put("text", "בוקר טוב {{weekday}}")))
        Thread.sleep(1_500) // the server's queue ticks every second
        assertTrue(api.waitForChanges(device, 2))

        fun sync(received: List<String> = emptyList(), results: List<TaskResult> = emptyList(), uploads: List<Upload> = emptyList()) =
            SyncProtocol.decode(api.sync(SyncProtocol.encode(SyncRequest(device, "בדיקה", "test", System.currentTimeMillis(),
                mapOf("battery" to 77, "network" to "wifi"), received, results, uploads))))

        val first = sync(uploads = listOf(Upload("c1", "inbox", """{"sender":"אמא","text":"מה נשמע?"}""", System.currentTimeMillis())))
        assertEquals(listOf("c1"), first.acceptedUploads)
        val task = first.tasks.single { it.id == id }
        val result = JSONObject(task.resultJson)
        assertTrue(result.getString("text").startsWith("בוקר טוב יום "))
        assertEquals("0501234567", result.getString("recipient"))

        // Phone stores it, later reports the outcome; duplicates are harmless.
        val second = sync(received = listOf(id), results = listOf(TaskResult(id, "done", "נשלח", System.currentTimeMillis())))
        assertEquals(listOf(id), second.acceptedResults)
        assertTrue(second.tasks.none { it.id == id })
        assertEquals(listOf(id), sync(results = listOf(TaskResult(id, "done", "נשלח", 0))).acceptedResults)

        // Wrong token is classified as an auth error (no retry loop).
        val bad = runCatching { SyncApi(base, "wrong").sync("{}") }.exceptionOrNull() as SyncException
        assertEquals(SyncException.Kind.AUTH, bad.kind)
        // Unreachable server is a retryable network error.
        val down = runCatching { SyncApi("http://127.0.0.1:1", token).health() }.exceptionOrNull() as SyncException
        assertTrue(down.retryable)
    }
}
