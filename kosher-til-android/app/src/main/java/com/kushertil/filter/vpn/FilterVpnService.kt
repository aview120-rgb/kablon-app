package com.kushertil.filter.vpn

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import androidx.core.app.NotificationCompat
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.kushertil.filter.App
import com.kushertil.filter.R
import com.kushertil.filter.data.Blocklist
import com.kushertil.filter.data.Prefs
import com.kushertil.filter.ui.MainActivity
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Local DNS-filtering VPN.
 *
 * Only DNS traffic is routed into the tunnel (a single /32 route to the virtual
 * DNS server), so the service inspects DNS queries and leaves all other traffic
 * untouched for performance. Blocked domains are answered with 0.0.0.0; allowed
 * queries are forwarded to an upstream resolver through a protected socket.
 */
class FilterVpnService : VpnService() {

    private val running = AtomicBoolean(false)
    private var tun: ParcelFileDescriptor? = null
    private var worker: Thread? = null
    private val forwarders = Executors.newFixedThreadPool(8)
    private val writeLock = Any()
    private var out: FileOutputStream? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIF_ID, buildNotification())
        if (running.compareAndSet(false, true)) {
            establish()
        }
        return START_STICKY
    }

    private fun establish() {
        try {
            val builder = Builder()
                .setSession(getString(R.string.service_name))
                .addAddress(VPN_ADDRESS, 32)
                .addDnsServer(VIRTUAL_DNS)
                .addRoute(VIRTUAL_DNS, 32)        // only DNS to our virtual server is captured
                .setBlocking(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                try { builder.addDisallowedApplication(packageName) } catch (_: Exception) {}
            }
            val pfd = builder.establish() ?: run {
                running.set(false); stopSelf(); return
            }
            tun = pfd
            out = FileOutputStream(pfd.fileDescriptor)
            val input = FileInputStream(pfd.fileDescriptor)
            worker = Thread({ loop(input) }, "kt-dns-loop").apply { isDaemon = true; start() }
        } catch (e: Exception) {
            Log.e(TAG, "establish failed", e)
            running.set(false)
            stopSelf()
        }
    }

    private fun loop(input: FileInputStream) {
        val buf = ByteArray(32767)
        while (running.get()) {
            val n = try { input.read(buf) } catch (e: Exception) { if (running.get()) Log.w(TAG, "read: ${e.message}"); break }
            if (n <= 0) continue
            val packet = buf.copyOf(n)
            try { handlePacket(packet) } catch (e: Exception) { Log.w(TAG, "handle: ${e.message}") }
        }
    }

    private fun handlePacket(packet: ByteArray) {
        if (packet.isEmpty()) return
        val version = (packet[0].toInt() and 0xF0) ushr 4
        if (version != 4) return                      // IPv6 not captured in this build
        val ihl = (packet[0].toInt() and 0x0F) * 4
        if (packet.size < ihl + 8) return
        val protocol = packet[9].toInt() and 0xFF
        if (protocol != 17) return                    // UDP only
        val dstPort = ((packet[ihl + 2].toInt() and 0xFF) shl 8) or (packet[ihl + 3].toInt() and 0xFF)
        if (dstPort != 53) return

        val udpLen = ((packet[ihl + 4].toInt() and 0xFF) shl 8) or (packet[ihl + 5].toInt() and 0xFF)
        val payloadOff = ihl + 8
        val payloadLen = (udpLen - 8).coerceAtMost(packet.size - payloadOff)
        if (payloadLen <= 0) return
        val dns = packet.copyOfRange(payloadOff, payloadOff + payloadLen)

        val host = Dns.queryName(dns)
        val blockNow = Prefs.filteringActiveNow(this) && Blocklist.isDomainBlocked(host)

        if (blockNow) {
            val resp = Dns.buildBlockedResponse(dns)
            writePacket(buildResponsePacket(packet, ihl, resp))
        } else {
            forwarders.execute { forward(packet, ihl, dns) }
        }
    }

    private fun forward(request: ByteArray, ihl: Int, dns: ByteArray) {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            protect(socket)
            socket.soTimeout = 5000
            val upstream = InetAddress.getByName(UPSTREAM_DNS)
            socket.send(DatagramPacket(dns, dns.size, InetSocketAddress(upstream, 53)))
            val respBuf = ByteArray(1500)
            val dp = DatagramPacket(respBuf, respBuf.size)
            socket.receive(dp)
            val resp = respBuf.copyOf(dp.length)
            writePacket(buildResponsePacket(request, ihl, resp))
        } catch (e: Exception) {
            Log.w(TAG, "forward ${e.message}")
        } finally {
            socket?.close()
        }
    }

    /** Build an IPv4/UDP response by swapping addresses/ports of [request]. */
    private fun buildResponsePacket(request: ByteArray, ihl: Int, dnsPayload: ByteArray): ByteArray {
        val totalLen = ihl + 8 + dnsPayload.size
        val pkt = ByteArray(totalLen)
        // Copy IP header, then fix fields.
        System.arraycopy(request, 0, pkt, 0, ihl)
        // total length
        pkt[2] = ((totalLen ushr 8) and 0xFF).toByte()
        pkt[3] = (totalLen and 0xFF).toByte()
        // TTL fresh, keep protocol
        pkt[8] = 64
        // swap src/dst IP (bytes 12..15 <-> 16..19)
        for (i in 0 until 4) {
            val s = request[12 + i]; val d = request[16 + i]
            pkt[12 + i] = d; pkt[16 + i] = s
        }
        // zero IP checksum then recompute
        pkt[10] = 0; pkt[11] = 0
        val ipck = checksum(pkt, 0, ihl)
        pkt[10] = ((ipck ushr 8) and 0xFF).toByte()
        pkt[11] = (ipck and 0xFF).toByte()

        // UDP header: swap ports, set length, checksum = 0 (optional for IPv4)
        val uOff = ihl
        pkt[uOff] = request[uOff + 2]; pkt[uOff + 1] = request[uOff + 3]     // src = old dst
        pkt[uOff + 2] = request[uOff]; pkt[uOff + 3] = request[uOff + 1]     // dst = old src
        val udpLen = 8 + dnsPayload.size
        pkt[uOff + 4] = ((udpLen ushr 8) and 0xFF).toByte()
        pkt[uOff + 5] = (udpLen and 0xFF).toByte()
        pkt[uOff + 6] = 0; pkt[uOff + 7] = 0
        System.arraycopy(dnsPayload, 0, pkt, uOff + 8, dnsPayload.size)
        return pkt
    }

    private fun checksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var i = offset
        val end = offset + length
        while (i + 1 < end) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) sum += (data[i].toInt() and 0xFF) shl 8
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }

    private fun writePacket(pkt: ByteArray) {
        val o = out ?: return
        synchronized(writeLock) {
            try { o.write(pkt) } catch (e: Exception) { Log.w(TAG, "write ${e.message}") }
        }
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, App.CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_active_title))
            .setContentText(getString(R.string.notif_active_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pi)
            .build()
    }

    override fun onRevoke() {
        // The user (or another VPN app) revoked us. Try to re-establish if policy says on.
        stopSelf()
        if (Prefs.isEnabled(this)) start(this)
    }

    override fun onDestroy() {
        running.set(false)
        try { worker?.interrupt() } catch (_: Exception) {}
        forwarders.shutdownNow()
        try { tun?.close() } catch (_: Exception) {}
        tun = null; out = null
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIF_ID)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "FilterVpn"
        const val ACTION_STOP = "com.kushertil.filter.STOP_VPN"
        private const val NOTIF_ID = 1001
        private const val VPN_ADDRESS = "10.111.222.2"
        private const val VIRTUAL_DNS = "10.111.222.1"
        private const val UPSTREAM_DNS = "1.1.1.1"

        fun start(ctx: Context) {
            val i = Intent(ctx, FilterVpnService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, FilterVpnService::class.java).setAction(ACTION_STOP))
        }
    }
}
