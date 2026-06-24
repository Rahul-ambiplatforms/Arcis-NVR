package com.arcisai.nvr.net

import android.content.Context
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
import kotlin.math.PI
import kotlin.math.sin

/**
 * Two-way audio via /cgi-bin/Chat WebSocket, routed through the NVR's HTTP server on port 80.
 *
 * Protocol (from NVR firmware analysis):
 *   1. Connect to NVR-IP:80 (LAN) or 127.0.0.1:tunnel-port (cloud via P2P main session).
 *   2. HTTP Upgrade → /cgi-bin/Chat with Host header = camera IP.
 *      The NVR's HTTP server acts as a transparent proxy: it routes to the camera whose IP
 *      matches the Host header (same mechanism used internally by the NVR firmware).
 *   3. Binary frames: raw G.711 A-law bytes at 8 kHz mono (160 bytes = 20 ms per frame)
 *   4. Text "Streamend" to close the session
 *
 * This makes LAN and cloud use the SAME code path — cloud just goes through the P2P tunnel
 * for the main HTTP session (ABD-xxx-RYNA, NVR port 80) instead of connecting directly.
 */
class CameraWsChatClient(
    private val host: String,
    private val port: Int,
    private val username: String,
    private val password: String,
    private val onReady: () -> Unit,
    private val onError: (String) -> Unit,
    /** Camera IP used as the HTTP Host header so the NVR proxy routes to the right camera.
     *  Defaults to [host] for backward-compat with direct-camera connections. */
    private val cameraHost: String = host,
) {
    private val client = OkHttpClient.Builder()
        .pingInterval(0, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()
    private var ws: WebSocket? = null
    private val closed = AtomicBoolean(false)
    @Volatile private var ready = false

    fun start() {
        Log.d(TAG, "start() ws://$host:$port/cgi-bin/Chat cameraHost=$cameraHost")
        val req = Request.Builder()
            .url("ws://$host:$port/cgi-bin/Chat")
            // No Authorization — camera Chat endpoint is unauthenticated per firmware API.
            // When routing via NVR relay (host != cameraHost), set Host to the camera's IP
            // so the camera's HTTP server accepts the upgrade request.
            .apply { if (cameraHost.isNotBlank() && cameraHost != host) header("Host", cameraHost) }
            .build()
        ws = client.newWebSocket(req, Listener())
    }

    fun sendAudio(g711: ByteArray) {
        if (closed.get() || !ready || g711.isEmpty()) return
        ws?.send(g711.toByteString())
    }

    fun stop() {
        if (!closed.compareAndSet(false, true)) return
        Log.d(TAG, "stop()")
        if (ready) runCatching { ws?.send("Streamend") }
        runCatching { ws?.close(1000, null) }
        runCatching { client.dispatcher.executorService.shutdown() }
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(TAG, "Chat WS open code=${response.code}")
            ready = true
            onReady()
        }
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            Log.d(TAG, "camera→ ${bytes.size}B hex=${bytes.hex().take(16)}")
        }
        override fun onMessage(webSocket: WebSocket, text: String) {
            Log.d(TAG, "Chat server msg: $text")
        }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "Chat failure: ${t.message} resp=${response?.code}")
            if (!closed.get()) onError(t.message ?: "Chat connection failed")
        }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "Chat closed code=$code reason=$reason")
            if (!closed.get()) onError("Chat closed ($code)")
        }
    }

    companion object {
        private const val TAG = "CameraWsChat"
        private const val SAMPLE_RATE = 8000
        private const val CHUNK_BYTES = 160  // 20 ms per frame

        /**
         * G.711 A-law encoder — identical to WsTalkbackClient.pcm16ToAlaw.
         * 16-bit PCM → 8-bit A-law byte.
         */
        fun pcm16ToAlaw(sample: Short): Byte {
            var s = sample.toInt()
            val mask: Int
            if (s >= 0) { mask = 0xD5 } else { mask = 0x55; s = s.inv() }
            val seg: Int; val mantissa: Int
            when {
                s >= 0x4000 -> { seg = 7; mantissa = (s ushr 7) and 0x0F }
                s >= 0x2000 -> { seg = 6; mantissa = (s ushr 6) and 0x0F }
                s >= 0x1000 -> { seg = 5; mantissa = (s ushr 5) and 0x0F }
                s >= 0x0800 -> { seg = 4; mantissa = (s ushr 4) and 0x0F }
                s >= 0x0400 -> { seg = 3; mantissa = (s ushr 3) and 0x0F }
                s >= 0x0200 -> { seg = 2; mantissa = (s ushr 2) and 0x0F }
                s >= 0x0100 -> { seg = 1; mantissa = (s ushr 1) and 0x0F }
                else        -> { seg = 0; mantissa =  s          and 0x0F }
            }
            return (((seg shl 4) or mantissa) xor mask).toByte()
        }

        /**
         * Read a 16-bit PCM WAV asset and encode it to G.711 A-law at 8 kHz mono.
         * Walks RIFF chunks so ffmpeg LIST/INFO metadata chunks are handled correctly.
         * Downmixes stereo and resamples if the file isn't already 8 kHz mono.
         * Falls back to a generated beep tone on any error.
         */
        fun readWavAssetToAlaw(context: Context, assetName: String): ByteArray {
            return try {
                readWavInternal(context, assetName)
            } catch (t: Throwable) {
                Log.e(TAG, "readWavAssetToAlaw($assetName): ${t.message} — using fallback tone")
                generateFallbackTone()
            }
        }

        private fun readWavInternal(context: Context, assetName: String): ByteArray {
            return context.assets.open(assetName).use { inp ->
                fun readFully(n: Int): ByteArray {
                    val b = ByteArray(n); var off = 0
                    while (off < n) { val r = inp.read(b, off, n - off); check(r >= 0) { "EOF" }; off += r }
                    return b
                }
                fun le16(b: ByteArray, o: Int) = ((b[o+1].toInt() and 0xFF) shl 8) or (b[o].toInt() and 0xFF)
                fun le32(b: ByteArray, o: Int) = ((b[o+3].toInt() and 0xFF) shl 24) or
                                                 ((b[o+2].toInt() and 0xFF) shl 16) or
                                                 ((b[o+1].toInt() and 0xFF) shl 8)  or
                                                  (b[o  ].toInt() and 0xFF)

                // 12-byte RIFF/WAVE header
                val riff = readFully(12)
                check(String(riff, 0, 4) == "RIFF" && String(riff, 8, 4) == "WAVE") { "Not a WAV" }

                // Walk chunks: collect fmt then data
                var channels = 1; var sampleRate = SAMPLE_RATE; var bitsPerSample = 16
                var pcmBytes: ByteArray? = null
                while (pcmBytes == null) {
                    val hdr = readFully(8)
                    val id = String(hdr, 0, 4)
                    val sz = le32(hdr, 4)
                    when (id) {
                        "fmt " -> {
                            val fmt = readFully(sz)
                            check(le16(fmt, 0) == 1) { "Only PCM WAV supported" }
                            channels      = le16(fmt, 2)
                            sampleRate    = le32(fmt, 4)
                            bitsPerSample = le16(fmt, 14)
                            check(bitsPerSample == 16) { "Only 16-bit WAV supported" }
                        }
                        "data" -> pcmBytes = readFully(sz)
                        else   -> inp.skip(if (sz % 2 == 0) sz.toLong() else sz.toLong() + 1)
                    }
                }

                val sampleCount = pcmBytes.size / 2

                // Read 16-bit LE samples and downmix to mono
                val rawMono = if (channels == 1) {
                    ShortArray(sampleCount) { i ->
                        ((pcmBytes[i * 2 + 1].toInt() shl 8) or (pcmBytes[i * 2].toInt() and 0xFF)).toShort()
                    }
                } else {
                    ShortArray(sampleCount / channels) { i ->
                        var s = 0L
                        for (c in 0 until channels) {
                            val base = (i * channels + c) * 2
                            s += ((pcmBytes[base + 1].toInt() shl 8) or (pcmBytes[base].toInt() and 0xFF)).toShort()
                        }
                        (s / channels).toShort()
                    }
                }

                // Resample to 8 kHz if needed (linear interpolation)
                val monoAt8k = if (sampleRate == SAMPLE_RATE) rawMono else {
                    val outLen = (rawMono.size.toLong() * SAMPLE_RATE / sampleRate).toInt()
                    ShortArray(outLen) { i ->
                        val srcPos = i.toDouble() * sampleRate / SAMPLE_RATE
                        val lo = srcPos.toInt().coerceIn(0, rawMono.size - 1)
                        val hi = (lo + 1).coerceIn(0, rawMono.size - 1)
                        val frac = srcPos - lo
                        (rawMono[lo] * (1 - frac) + rawMono[hi] * frac).toInt().toShort()
                    }
                }

                // Encode to G.711 A-law
                ByteArray(monoAt8k.size) { i -> pcm16ToAlaw(monoAt8k[i]) }
            }
        }

        private fun generateFallbackTone(durationMs: Int = 5000): ByteArray {
            val totalSamples = SAMPLE_RATE * durationMs / 1000
            val halfPeriod = SAMPLE_RATE / 2
            return ByteArray(totalSamples) { i ->
                val freq = if ((i / halfPeriod) % 2 == 0) 1000.0 else 2000.0
                val pcm = (Short.MAX_VALUE * 0.7 * sin(2 * PI * freq * i / SAMPLE_RATE)).toInt().toShort()
                pcm16ToAlaw(pcm)
            }
        }
    }
}
