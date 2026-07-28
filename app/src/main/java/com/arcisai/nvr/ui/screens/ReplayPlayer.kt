package com.arcisai.nvr.ui.screens

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.arcisai.nvr.net.WsReplayClient
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

private class Frame(val codec: String, val isKey: Boolean, val w: Int, val h: Int, val data: ByteArray, val timestampSec: Long)

/**
 * Plays an NVR recording over the WebSocket :10000 replay protocol and decodes the
 * H.265/H.264 Annex-B frames with MediaCodec onto a SurfaceView. See WsReplayClient
 * and P2P_LIVE_PROTOCOL_EXACT.md §9. [host]:[port] is the NVR HTTP/stream host —
 * :10000 on LAN; in Remote mode it needs a P2P tunnel to :10000 (not yet wired).
 */
private class ReplayController(
    val host: String, val port: Int, val user: String, val pass: String,
    val channel: Int, val begin: Long, val end: Long,
    val onStatus: (String) -> Unit,
) {
    private val queue = LinkedBlockingQueue<Frame>(120)
    @Volatile private var running = false
    private var client: WsReplayClient? = null
    private var thread: Thread? = null

    fun start(surface: Surface) {
        if (running) return
        running = true
        client = WsReplayClient(
            host, port, user, pass, channel, begin, end,
            onFrame = { codec, isKey, w, h, data, timestampSec ->
                if (running) { if (!queue.offer(Frame(codec, isKey, w, h, data, timestampSec))) queue.poll() }
            },
            onStatus = onStatus,
            onError = { msg -> if (running) onStatus("Error: $msg") },
        ).also { it.start() }
        thread = Thread { decodeLoop(surface) }.apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        runCatching { client?.stop() }
        thread?.interrupt()
    }

    private fun decodeLoop(surface: Surface) {
        var codec: MediaCodec? = null
        var started = false
        var pts = 0L
        var frameIndex = 0L
        var firstNanos = 0L
        var firstTs = -1L        // first valid firmware timestamp (seconds); -1 = use counter
        val info = MediaCodec.BufferInfo()
        try {
            while (running) {
                val f = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                if (!started) {
                    if (!f.isKey) continue                 // must begin on a keyframe
                    val w = if (f.w in 16..8192) f.w else 1920
                    val h = if (f.h in 16..8192) f.h else 1080
                    codec = MediaCodec.createDecoderByType(f.codec)
                    val fmt = MediaFormat.createVideoFormat(f.codec, w, h)
                    codec.configure(fmt, surface, null, 0)
                    codec.start()
                    started = true
                    firstNanos = System.nanoTime()
                    // Use firmware timestamp if it's within ±5 min of the requested range
                    if (f.timestampSec in (begin - 300)..(end + 300)) firstTs = f.timestampSec
                    onStatus("Playing")
                }

                // Compute PTS: prefer firmware timestamp; fall back to 25 fps counter
                pts = if (firstTs >= 0 && f.timestampSec >= firstTs) {
                    (f.timestampSec - firstTs) * 1_000_000L
                } else {
                    frameIndex * 40_000L                   // 25 fps fallback
                }
                frameIndex++

                // Real-time throttle: don't play faster than wall-clock time
                val targetNanos = firstNanos + pts * 1000L
                val sleepMs = (targetNanos - System.nanoTime()) / 1_000_000L
                if (sleepMs > 2L) Thread.sleep(sleepMs)

                val c = codec ?: continue
                val inIdx = c.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val buf = c.getInputBuffer(inIdx)
                    buf?.clear(); buf?.put(f.data)
                    c.queueInputBuffer(inIdx, 0, f.data.size, pts, 0)
                }
                var outIdx = c.dequeueOutputBuffer(info, 0)
                while (outIdx >= 0) {
                    c.releaseOutputBuffer(outIdx, true)    // render to surface
                    outIdx = c.dequeueOutputBuffer(info, 0)
                }
            }
        } catch (_: InterruptedException) {
        } catch (t: Throwable) {
            android.util.Log.w("ReplayPlayer", "decode error: ${t.message}")
            if (running) onStatus("Couldn't play this recording. Please try again.")
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
        }
    }
}

@Composable
fun ReplayPlayer(
    host: String, port: Int, user: String, pass: String,
    channel: Int, beginEpoch: Long, endEpoch: Long,
    modifier: Modifier = Modifier,
) {
    var status by remember(host, channel, beginEpoch) { mutableStateOf("Connecting…") }
    var playing by remember(host, channel, beginEpoch) { mutableStateOf(false) }
    val controller = remember(host, channel, beginEpoch) {
        ReplayController(host, port, user, pass, channel, beginEpoch, endEpoch) { s ->
            status = s; if (s == "Playing") playing = true
        }
    }

    DisposableEffect(controller) { onDispose { controller.stop() } }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(h: SurfaceHolder) { controller.start(h.surface) }
                        override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {}
                        override fun surfaceDestroyed(h: SurfaceHolder) { controller.stop() }
                    })
                }
            },
        )
        if (!playing) {
            androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (!status.startsWith("Error") && !status.startsWith("Decode")) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(32.dp))
                    androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
                }
                Text(status, color = Color.White.copy(alpha = 0.9f), fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 24.dp))
            }
        }
    }
}
