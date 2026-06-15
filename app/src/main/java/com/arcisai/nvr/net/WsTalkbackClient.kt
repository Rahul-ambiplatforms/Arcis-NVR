package com.arcisai.nvr.net

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Two-way audio talkback over the NVR's IOT WebSocket protocol (port 10000).
 *
 * Protocol: ARQ open → IOT OPEN_REQ/RES → AES-128 AUTH → VCON_REQ(CREATE) →
 *   VCON_REQ(DATA) [repeated with G.711 μ-law chunks] → VCON_REQ(DESTROY).
 *
 * Same ARQ/IOT/P2PK layers as WsReplayClient (validated). VCON payload layout
 * inferred from JS constants (VCON_CMD_CREATE=1, DATA=2, DESTROY=3, app_name=32):
 *   CREATE/DESTROY: cmd(4LE) + channel(4LE) + app_name(32, NUL-padded)
 *   DATA:           cmd(4LE) + g711_bytes
 *
 * Usage:
 *   val c = WsTalkbackClient(host, 10000, "admin", "", channel, onReady={...}, onError={...})
 *   c.start()                   // connects, auths, sends VCON_CREATE; fires onReady on success
 *   c.sendAudio(g711ByteArray)  // call repeatedly from audio-capture thread while onReady
 *   c.stop()                    // sends VCON_DESTROY, closes
 */
class WsTalkbackClient(
    private val host: String,
    private val port: Int,
    private val username: String,
    private val password: String,
    private val channel: Int,
    private val onReady: () -> Unit,
    private val onError: (String) -> Unit,
) {
    private val client = OkHttpClient.Builder()
        .pingInterval(0, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()
    private var ws: WebSocket? = null
    private val sid = kotlin.random.Random.nextInt(1, 10_001)
    private var tick = 0
    @Volatile private var state = St.OPENING
    private val closed = AtomicBoolean(false)
    private var pingThread: Thread? = null

    private enum class St { OPENING, IOT, AUTH, READY }

    fun start() {
        val req = Request.Builder().url("ws://$host:$port").build()
        ws = client.newWebSocket(req, Listener())
    }

    fun sendAudio(g711: ByteArray) {
        if (state != St.READY || closed.get() || g711.isEmpty()) return
        val p = ByteArray(4 + g711.size)
        le32(VCON_DATA).copyInto(p, 0)
        g711.copyInto(p, 4)
        runCatching { sendApi(VCON_REQ, p) }
    }

    fun stop() {
        if (!closed.compareAndSet(false, true)) return
        pingThread?.interrupt()
        runCatching { sendApi(VCON_REQ, vconCtrlPayload(VCON_DESTROY)) }
        runCatching { ws?.close(1000, null) }
        runCatching { client.dispatcher.executorService.shutdown() }
    }

    // ── byte helpers (little-endian) ─────────────────────────────────────────

    private fun le32(v: Int) = byteArrayOf(
        (v and 0xff).toByte(), (v ushr 8 and 0xff).toByte(),
        (v ushr 16 and 0xff).toByte(), (v ushr 24 and 0xff).toByte())

    private fun u32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or
        ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)

    private fun iotHdr(cmd: Int, payload: ByteArray): ByteArray {
        val h = ByteArray(32 + payload.size)
        h[0] = 0xAB.toByte(); h[1] = 0xBC.toByte(); h[2] = 0xCD.toByte(); h[3] = 0xDE.toByte()
        h[4] = cmd.toByte(); h[11] = 1
        le32(sid).copyInto(h, 16)
        le32(payload.size).copyInto(h, 28)
        payload.copyInto(h, 32)
        return h
    }

    private fun apiHdr(cmd: Int, tk: Int, payload: ByteArray): ByteArray {
        val h = ByteArray(24 + payload.size)
        h[0] = 0x50; h[1] = 0x32; h[2] = 0x50; h[3] = 0x4B
        le32(1).copyInto(h, 4)
        le32(tk).copyInto(h, 8)
        le32(cmd).copyInto(h, 12)
        le32(payload.size).copyInto(h, 20)
        payload.copyInto(h, 24)
        return h
    }

    private fun sendArq(payload: ByteArray) {
        val hdr = ByteArray(8)
        hdr[0] = 0xCE.toByte(); hdr[1] = 0xFA.toByte(); hdr[2] = 0xEF.toByte(); hdr[3] = 0xFE.toByte()
        le32(payload.size).copyInto(hdr, 4)
        ws?.send(hdr.toByteString())
        ws?.send(payload.toByteString())
    }

    private fun sendApi(cmd: Int, payload: ByteArray) {
        tick += 1
        sendArq(iotHdr(IOT_DATA, apiHdr(cmd, tick, payload)))
    }

    // ── AES-128-ECB auth (identical to WsReplayClient) ───────────────────────

    private fun aesHalf(s: String): ByteArray {
        val c = Cipher.getInstance("AES/ECB/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(AES_KEY.toByteArray(Charsets.UTF_8), "AES"))
        val p = ByteArray(16)
        s.toByteArray(Charsets.UTF_8).copyInto(p, 0, 0, minOf(16, s.length))
        return c.doFinal(p)
    }

    private fun authPayload(): ByteArray {
        val u1 = aesHalf(username.take(16))
        val u2 = aesHalf(if (username.length > 16) username.substring(16) else "")
        val p1 = aesHalf(password.take(16))
        val p2 = aesHalf(if (password.length > 16) password.substring(16) else "")
        return ByteArray(64).also {
            u1.copyInto(it, 0); u2.copyInto(it, 16); p1.copyInto(it, 32); p2.copyInto(it, 48)
        }
    }

    // ── VCON payloads ─────────────────────────────────────────────────────────

    private fun vconCtrlPayload(subCmd: Int): ByteArray {
        val p = ByteArray(40)                   // cmd(4) + channel(4) + app_name(32)
        le32(subCmd).copyInto(p, 0)
        le32(channel).copyInto(p, 4)
        val name = "android".toByteArray(Charsets.US_ASCII)
        name.copyInto(p, 8, 0, minOf(name.size, 31))
        return p
    }

    // ── WebSocket listener ────────────────────────────────────────────────────

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            val f = ByteArray(20)
            for (i in 0 until 16) f[i] = OPEN_MAGIC[i].toByte()
            le32(sid).copyInto(f, 16)
            webSocket.send(f.toByteString())
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (closed.get()) return
            val b = bytes.toByteArray()
            if (b.isEmpty()) return
            if ((b[0].toInt() and 0xff) == 0xCE) return  // ARQ header frame — discard
            if (state == St.OPENING) {
                state = St.IOT
                val openReq = ByteArray(8); le32(sid).copyInto(openReq, 0)
                sendArq(iotHdr(IOT_OPEN_REQ, openReq))
                return
            }
            runCatching { handlePacket(b) }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!closed.get()) onError(t.message ?: "Connection error")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!closed.get()) onError("Connection closed")
        }
    }

    private fun handlePacket(b: ByteArray) {
        if (b.size < 32 || (b[0].toInt() and 0xff) != 0xAB) return
        val iotCmd = b[4].toInt() and 0xff
        if (iotCmd == IOT_OPEN_RES) {
            if (u32(b, 24) == 0) { state = St.AUTH; sendApi(AUTH_REQ, authPayload()) }
            else onError("Link open failed")
            return
        }
        if (iotCmd != IOT_DATA && iotCmd != IOT_DATA_PRIOR) return
        if (b.size < 56) return
        val inner = b.copyOfRange(32, b.size)
        if (inner.size < 24) return
        if ((inner[0].toInt() and 0xff) != 0x50 || (inner[1].toInt() and 0xff) != 0x32) return
        val apiCmd = u32(inner, 12); val result = u32(inner, 16)
        when (apiCmd) {
            AUTH_RSP -> if (result == 0) {
                sendApi(VCON_REQ, vconCtrlPayload(VCON_CREATE))
                startPing()
            } else onError("Auth failed (code $result)")
            VCON_RSP -> if (result == 0) { state = St.READY; onReady() }
                        else onError("Talkback rejected (code $result)")
        }
    }

    private fun startPing() {
        pingThread = Thread {
            try {
                while (!closed.get()) {
                    Thread.sleep(10_000)
                    if (closed.get()) break
                    val ping = ByteArray(96); le32(sid).copyInto(ping, 0)
                    sendArq(iotHdr(IOT_PING, ping))
                }
            } catch (_: InterruptedException) { }
        }.apply { isDaemon = true; start() }
    }

    companion object {
        private val OPEN_MAGIC = intArrayOf(
            0xd9, 0xff, 0xcc, 0x02, 0x8c, 0x38, 0xee, 0xd2,
            0xd1, 0x99, 0xac, 0x60, 0x26, 0x94, 0x7f, 0xae)
        private const val AES_KEY = "~!JUAN*&Vision-="
        private const val IOT_PING = 17
        private const val IOT_DATA = 19
        private const val IOT_OPEN_REQ = 20
        private const val IOT_OPEN_RES = 21
        private const val IOT_DATA_PRIOR = 43
        private const val AUTH_REQ = 10
        private const val AUTH_RSP = 11
        private const val VCON_REQ = 50
        private const val VCON_RSP = 51
        private const val VCON_CREATE = 1
        private const val VCON_DATA = 2
        private const val VCON_DESTROY = 3

        /**
         * G.711 A-law encoder (ITU-T G.711 / G.191).
         * Converts a 16-bit PCM sample to an 8-bit A-law byte.
         * NVR audio pipeline uses A-law (confirmed via web-UI G711.alawdecode).
         */
        fun pcm16ToAlaw(sample: Short): Byte {
            var ix = sample.toInt()
            val mask: Int
            if (ix >= 0) { ix = ix shr 4; mask = 0xD5 }
            else         { ix = ix.inv() shr 4; mask = 0x55 }
            val exp = when {
                ix >= 1024 -> { ix = ix shr 6; 7 }
                ix >= 512  -> { ix = ix shr 5; 6 }
                ix >= 256  -> { ix = ix shr 4; 5 }
                ix >= 128  -> { ix = ix shr 3; 4 }
                ix >= 64   -> { ix = ix shr 2; 3 }
                ix >= 32   -> { ix = ix shr 1; 2 }
                ix >= 16   -> 1
                else       -> 0
            }
            return (((exp shl 4) or (ix and 0x0F)) xor mask).toByte()
        }
    }
}
