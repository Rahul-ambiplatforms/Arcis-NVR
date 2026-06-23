package com.arcisai.nvr.net

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
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
 * Audio-listen client: opens the NVR WebSocket live stream on port 10000 and
 * plays the camera microphone audio through the phone speaker.
 *
 * Protocol: ARQ → IOT OPEN → AES-128 AUTH → LIVE_REQ(channel, 0, START) →
 *   receive FRAM/FRAN media frames (headtype=0 live, frametype=0 audio) →
 *   G.711 A-law decode → AudioTrack 8000 Hz mono PCM-16.
 *
 * Identical ARQ/IOT/AUTH layers to WsReplayClient and WsTalkbackClient.
 */
class WsAudioListenClient(
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
    @Volatile private var audioTrack: AudioTrack? = null

    private enum class St { OPENING, IOT, AUTH, LIVE, STREAMING }

    fun start() {
        Log.d(TAG, "start host=$host port=$port channel=$channel")
        val req = Request.Builder().url("ws://$host:$port").build()
        ws = client.newWebSocket(req, Listener())
    }

    fun stop() {
        if (!closed.compareAndSet(false, true)) return
        Log.d(TAG, "stop state=$state")
        pingThread?.interrupt()
        if (state == St.LIVE || state == St.STREAMING) {
            runCatching { sendApi(LIVE_REQ, livePayload(LIVE_CMD_STOP)) }
        }
        runCatching { ws?.close(1000, null) }
        runCatching { client.dispatcher.executorService.shutdown() }
        runCatching { audioTrack?.stop(); audioTrack?.release(); audioTrack = null }
    }

    // ── audio output ──────────────────────────────────────────────────────────

    private fun ensureAudioTrack() {
        if (audioTrack != null) return
        val minBuf = AudioTrack.getMinBufferSize(
            8000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(8000)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build())
            .setBufferSizeInBytes(maxOf(minBuf, 3200) * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        audioTrack?.play()
    }

    private fun playAlaw(data: ByteArray, offset: Int, len: Int) {
        ensureAudioTrack()
        val pcm = ByteArray(len * 2)
        for (i in 0 until len) {
            val s = alawToPcm16(data[offset + i])
            pcm[i * 2]     = (s.toInt() and 0xFF).toByte()
            pcm[i * 2 + 1] = (s.toInt() ushr 8 and 0xFF).toByte()
        }
        audioTrack?.write(pcm, 0, pcm.size, AudioTrack.WRITE_NON_BLOCKING)
    }

    private fun playMulaw(data: ByteArray, offset: Int, len: Int) {
        ensureAudioTrack()
        val pcm = ByteArray(len * 2)
        for (i in 0 until len) {
            val s = mulawToPcm16(data[offset + i])
            pcm[i * 2]     = (s.toInt() and 0xFF).toByte()
            pcm[i * 2 + 1] = (s.toInt() ushr 8 and 0xFF).toByte()
        }
        audioTrack?.write(pcm, 0, pcm.size, AudioTrack.WRITE_NON_BLOCKING)
    }

    // ── byte helpers ──────────────────────────────────────────────────────────

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

    /** live_req payload: channel(4LE) + streamId(4LE) + live_cmd(4LE) */
    private fun livePayload(liveCmd: Int): ByteArray {
        val p = ByteArray(12)
        le32(channel).copyInto(p, 0)
        le32(0).copyInto(p, 4)          // streamId 0 = main stream
        le32(liveCmd).copyInto(p, 8)
        return p
    }

    // ── AES-128 auth (identical to WsTalkbackClient / WsReplayClient) ─────────

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

    // ── WebSocket listener ────────────────────────────────────────────────────

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(TAG, "WS open → ARQ open_conn sid=$sid")
            val f = ByteArray(20)
            for (i in 0 until 16) f[i] = OPEN_MAGIC[i].toByte()
            le32(sid).copyInto(f, 16)
            webSocket.send(f.toByteString())
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (closed.get()) return
            val b = bytes.toByteArray()
            if (b.isEmpty()) return
            if ((b[0].toInt() and 0xff) == 0xCE) return    // ARQ header — discard
            if (state == St.OPENING) {
                state = St.IOT
                val openReq = ByteArray(8); le32(sid).copyInto(openReq, 0)
                sendArq(iotHdr(IOT_OPEN_REQ, openReq))
                return
            }
            runCatching { handlePacket(b) }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "WS failure: ${t.message}")
            if (!closed.get()) onError(t.message ?: "Connection error")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!closed.get()) onError("Connection closed ($reason)")
        }
    }

    private fun handlePacket(b: ByteArray) {
        if (b.size < 32 || (b[0].toInt() and 0xff) != 0xAB) return
        val iotCmd = b[4].toInt() and 0xff

        if (iotCmd == IOT_OPEN_RES) {
            val ecode = u32(b, 24)
            Log.d(TAG, "IOT_OPEN_RES ecode=$ecode")
            if (ecode == 0) { state = St.AUTH; sendApi(AUTH_REQ, authPayload()) }
            else onError("Link open failed ($ecode)")
            return
        }

        if (iotCmd != IOT_DATA && iotCmd != IOT_DATA_PRIOR) return
        if (b.size < 56) return

        val inner = b.copyOfRange(32, b.size)
        if (inner.size < 4) return

        val m0 = inner[0].toInt() and 0xff
        val m1 = inner[1].toInt() and 0xff
        val m2 = inner[2].toInt() and 0xff
        val m3 = inner[3].toInt() and 0xff

        // P2PK API response
        if (m0 == 0x50 && m1 == 0x32 && m2 == 0x50 && m3 == 0x4B) {
            if (inner.size < 24) return
            val apiCmd = u32(inner, 12)
            val result = u32(inner, 16)
            Log.d(TAG, "P2PK apiCmd=$apiCmd result=$result")
            when (apiCmd) {
                AUTH_RSP -> if (result == 0) {
                    Log.d(TAG, "AUTH OK → LIVE_REQ channel=$channel")
                    state = St.LIVE
                    sendApi(LIVE_REQ, livePayload(LIVE_CMD_START))
                    startPing()
                } else onError("Auth failed ($result)")
                LIVE_RSP -> {
                    // live_res: channel[4] + streamid[4] + live_cmd[4] at hdr_len(24) offset
                    if (inner.size < 24 + 12) return
                    val liveCmd = u32(inner, 24 + 8)
                    Log.d(TAG, "LIVE_RSP liveCmd=$liveCmd result=$result")
                    if (liveCmd == LIVE_CMD_START) {
                        if (result == 0) { state = St.STREAMING; onReady() }
                        else onError("Stream open failed ($result)")
                    }
                }
            }
            return
        }

        // Media frames (FRAN = 4E 41 52 46, FRAM = 4D 41 52 46)
        val isFran = m0 == 0x4E && m1 == 0x41 && m2 == 0x52 && m3 == 0x46
        val isFram = m0 == 0x4D && m1 == 0x41 && m2 == 0x52 && m3 == 0x46
        if (!isFran && !isFram) return

        // FRAN has a 40-byte p2p_frame_head_2 prefix; FRAM starts at the p2p_frame_head directly.
        var pos = if (isFran) 40 else 0
        if (inner.size < pos + 24) return

        val headtype = u32(inner, pos + 8)     // p2p_frame_head.headtype at bytes 8..11 (spec §5.1)
        pos += 24                               // skip p2p_frame_head (24 bytes)

        if (headtype != HEAD_TYPE_LIVE) {
            Log.v(TAG, "skip non-live frame headtype=$headtype")
            return
        }

        if (inner.size < pos + 8) return
        val frametype = u32(inner, pos)         // live_head.frametype
        pos += 8                                // skip live_head (8 bytes)

        if (frametype != FRAME_TYPE_AUDIO) return  // only audio

        // audio_param (24 bytes): enc[0..7] ASCII + samplerate[8..11] + samplewidth[12..15]
        //                          + channels[16..19] + compress[20..23]
        // +8 reserved bytes before body (same +8 gap as video — spec §5.5)
        if (inner.size < pos + 32) return
        val paramBase = pos
        val encStr = String(inner, paramBase, 8, Charsets.US_ASCII).trimEnd(' ')
        val sampleRate = u32(inner, paramBase + 8)
        pos += 32

        if (pos >= inner.size) return
        val dataLen = inner.size - pos
        Log.d(TAG, "audio ${dataLen}B enc='$encStr' sampleRate=$sampleRate")

        when {
            encStr.startsWith("G711A", ignoreCase = true) -> playAlaw(inner, pos, dataLen)
            encStr.startsWith("G711U", ignoreCase = true) -> playMulaw(inner, pos, dataLen)
            encStr.isEmpty() -> playAlaw(inner, pos, dataLen)  // pre-spec firmware: assume A-law
            else -> Log.w(TAG, "Unsupported audio codec '$encStr' — skipping frame")
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
        private const val TAG = "WsAudioListen"
        private val OPEN_MAGIC = intArrayOf(
            0xd9, 0xff, 0xcc, 0x02, 0x8c, 0x38, 0xee, 0xd2,
            0xd1, 0x99, 0xac, 0x60, 0x26, 0x94, 0x7f, 0xae)
        private const val AES_KEY        = "~!JUAN*&Vision-="
        private const val IOT_PING       = 17
        private const val IOT_DATA       = 19
        private const val IOT_OPEN_REQ   = 20
        private const val IOT_OPEN_RES   = 21
        private const val IOT_DATA_PRIOR = 43
        private const val AUTH_REQ       = 10
        private const val AUTH_RSP       = 11
        private const val LIVE_REQ       = 30
        private const val LIVE_RSP       = 31
        private const val LIVE_CMD_START = 2
        private const val LIVE_CMD_STOP  = 1
        private const val HEAD_TYPE_LIVE = 0
        private const val FRAME_TYPE_AUDIO = 0

        /** ITU-T G.711 mu-law → linear PCM-16. */
        fun mulawToPcm16(mulaw: Byte): Short {
            val mu = (mulaw.toInt() and 0xFF) xor 0xFF
            val sign = if ((mu and 0x80) != 0) 1 else -1
            val exp = (mu and 0x70) ushr 4
            val mantissa = mu and 0x0F
            val magnitude = ((mantissa shl 3) + 0x84) shl exp
            return (sign * (magnitude - 0x84)).toShort()
        }

        /** ITU-T G.711 A-law → linear PCM-16. */
        fun alawToPcm16(alaw: Byte): Short {
            val a = (alaw.toInt() and 0xFF) xor 0x55
            var t = (a and 0x0F) shl 4
            val seg = (a and 0x70) ushr 3
            when (seg) {
                0    -> t += 8
                1    -> t += 0x108
                else -> { t += 0x108; t = t shl (seg - 1) }
            }
            return (if ((a and 0x80) != 0) t else -t).toShort()
        }
    }
}
