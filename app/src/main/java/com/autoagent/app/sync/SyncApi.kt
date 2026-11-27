package com.autoagent.app.sync

import com.autoagent.app.util.L
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Errors are classified so the engine knows whether to retry (network/server) or stop (auth/config). */
class SyncException(val kind: Kind, message: String, val httpCode: Int = 0) : Exception(message) {
    enum class Kind { NETWORK, SERVER, AUTH, CLIENT, CONFIG }

    val retryable: Boolean get() = kind == Kind.NETWORK || kind == Kind.SERVER
}

/** Minimal HTTP client on HttpURLConnection (no extra libraries). Blocking: call from Dispatchers.IO. */
class SyncApi(baseUrl: String, private val token: String) {
    private val base = baseUrl.trim().trimEnd('/')

    init {
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            throw SyncException(SyncException.Kind.CONFIG, L.t("Server address must start with http:// or https://", "כתובת השרת חייבת להתחיל ב-http:// או https://"))
        }
    }

    fun sync(body: String): String = request("POST", "/api/v1/sync", body, readTimeoutMs = 30_000)

    /** Long poll; true = something is waiting on the server. */
    fun waitForChanges(deviceId: String, timeoutSec: Int = 25): Boolean {
        val out = request("GET", "/api/v1/wait?deviceId=" + java.net.URLEncoder.encode(deviceId, "UTF-8") + "&timeout=$timeoutSec", null,
            readTimeoutMs = (timeoutSec + 15) * 1000)
        return out.contains("\"changed\":true")
    }

    fun health(): String = request("GET", "/health", null, readTimeoutMs = 10_000)

    private fun request(method: String, path: String, body: String?, readTimeoutMs: Int): String {
        val conn = try {
            (URL(base + path).openConnection() as HttpURLConnection)
        } catch (e: Exception) {
            throw SyncException(SyncException.Kind.CONFIG, L.t("Invalid server address", "כתובת שרת לא תקינה") + ": ${e.message}")
        }
        try {
            conn.requestMethod = method
            conn.connectTimeout = 10_000
            conn.readTimeout = readTimeoutMs
            conn.useCaches = false
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Accept", "application/json")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val text = (if (code < 400) conn.inputStream else conn.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code in 200..299) return text
            val serverMsg = SyncProtocol.errorMessage(text) ?: "HTTP $code"
            throw when {
                code == 401 || code == 403 -> SyncException(SyncException.Kind.AUTH, L.t("Wrong access token", "אסימון גישה שגוי") + " ($serverMsg)", code)
                code == 408 || code == 429 || code >= 500 -> SyncException(SyncException.Kind.SERVER, L.t("Server error", "שגיאת שרת") + " $code: $serverMsg", code)
                else -> SyncException(SyncException.Kind.CLIENT, L.t("Request rejected", "הבקשה נדחתה") + " $code: $serverMsg", code)
            }
        } catch (e: SyncException) {
            throw e
        } catch (e: UnknownHostException) {
            throw SyncException(SyncException.Kind.NETWORK, L.t("Server name not found (DNS)", "שם השרת לא נמצא (DNS)"))
        } catch (e: SocketTimeoutException) {
            throw SyncException(SyncException.Kind.NETWORK, L.t("Server did not answer in time", "השרת לא ענה בזמן"))
        } catch (e: SSLException) {
            throw SyncException(SyncException.Kind.NETWORK, L.t("Secure connection failed (TLS)", "החיבור המאובטח נכשל (TLS)") + ": ${e.message}")
        } catch (e: IOException) {
            throw SyncException(SyncException.Kind.NETWORK, L.t("Network error", "שגיאת רשת") + ": ${e.message ?: e.javaClass.simpleName}")
        } finally {
            conn.disconnect()
        }
    }
}
