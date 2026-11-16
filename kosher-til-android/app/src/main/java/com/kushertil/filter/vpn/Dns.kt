package com.kushertil.filter.vpn

import java.nio.ByteBuffer

/**
 * Minimal DNS message helpers: read the queried host from a query, and build a
 * "blocked" response (A record pointing at 0.0.0.0 with a short TTL).
 *
 * Only enough of RFC 1035 is implemented to inspect the first question and
 * answer A/AAAA queries with a sinkhole address. Compression pointers in the
 * QUESTION are not expected (clients don't send them) and are handled defensively.
 */
object Dns {

    /** Parse the first question's name from a raw DNS message. Null if malformed. */
    fun queryName(msg: ByteArray): String? {
        try {
            if (msg.size < 12) return null
            val qdCount = ((msg[4].toInt() and 0xFF) shl 8) or (msg[5].toInt() and 0xFF)
            if (qdCount < 1) return null
            var pos = 12
            val sb = StringBuilder()
            var guard = 0
            while (pos < msg.size) {
                val len = msg[pos].toInt() and 0xFF
                if (len == 0) break
                if (len and 0xC0 != 0) return null // compression pointer in question: bail
                pos++
                if (pos + len > msg.size) return null
                if (sb.isNotEmpty()) sb.append('.')
                sb.append(String(msg, pos, len, Charsets.US_ASCII))
                pos += len
                if (++guard > 127) return null
            }
            return if (sb.isEmpty()) null else sb.toString().lowercase()
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * Build a response for a blocked domain: echoes the question and adds a
     * single A answer = 0.0.0.0 (TTL 60). For AAAA queries we still return an
     * A-less NODATA-style answer (no answer records, flags set) so the client
     * gets a definitive, non-routable result.
     */
    fun buildBlockedResponse(query: ByteArray): ByteArray {
        // Find end of question section.
        var pos = 12
        while (pos < query.size) {
            val len = query[pos].toInt() and 0xFF
            if (len == 0) { pos++; break }
            if (len and 0xC0 != 0) { pos += 2; break }
            pos += len + 1
        }
        val qEnd = (pos + 4).coerceAtMost(query.size) // + QTYPE(2) + QCLASS(2)
        val qtype = if (qEnd >= pos + 2 && pos + 1 < query.size)
            ((query[pos].toInt() and 0xFF) shl 8) or (query[pos + 1].toInt() and 0xFF) else 1

        val isA = qtype == 1
        val question = query.copyOfRange(0, qEnd)

        val answerLen = if (isA) 16 else 0 // name-ptr(2)+type(2)+class(2)+ttl(4)+rdlen(2)+rdata(4)
        val out = ByteBuffer.allocate(question.size + answerLen)
        out.put(question)

        // Rewrite header: QR=1, AA=1, RA=1, RCODE=0; ANCOUNT = isA ? 1 : 0.
        out.put(2, (0x81).toByte()) // QR=1, Opcode=0, AA=1
        out.put(3, (0x80).toByte()) // RA=1, RCODE=0
        val anCount = if (isA) 1 else 0
        out.put(6, ((anCount ushr 8) and 0xFF).toByte())
        out.put(7, (anCount and 0xFF).toByte())
        // NSCOUNT / ARCOUNT = 0
        out.put(8, 0); out.put(9, 0); out.put(10, 0); out.put(11, 0)

        if (isA) {
            out.position(question.size)
            out.put(0xC0.toByte()); out.put(0x0C.toByte()) // pointer to name at offset 12
            out.put(0x00); out.put(0x01)                   // TYPE A
            out.put(0x00); out.put(0x01)                   // CLASS IN
            out.put(0x00); out.put(0x00); out.put(0x00); out.put(0x3C) // TTL 60
            out.put(0x00); out.put(0x04)                   // RDLENGTH 4
            out.put(0x00); out.put(0x00); out.put(0x00); out.put(0x00) // 0.0.0.0
        }
        return out.array()
    }
}
