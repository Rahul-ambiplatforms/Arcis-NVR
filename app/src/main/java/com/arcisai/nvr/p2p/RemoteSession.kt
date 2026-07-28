package com.arcisai.nvr.p2p

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One end-to-end P2P session for one service_id (eg. "ABD-400289-RYNA" for
 * the NVR HTTP API, or "ABD-400289-RYNA-c0" for channel 0 RTSP).
 *
 * Flow:
 *   1. Create JuiceAgent + start gathering candidates.
 *   2. Wait for all candidates to be gathered locally.
 *   3. POST /api/match to the signaling server with our SDP + candidates.
 *   4. Apply the provider's SDP + candidates to the JuiceAgent.
 *   5. When agent connects, expose a localhost TCP listener — every accepted
 *      connection has its bytes pumped to the provider over libjuice; bytes
 *      coming back are forwarded to the TCP client.
 *
 * The local port number is callback-reported so the app can plug it into
 * OkHttp / libVLC URLs (e.g. http://127.0.0.1:<port>/netsdk/Channel).
 */
class RemoteSession(
    private val serviceId: String,
    private val cfg: RemoteConfig,
) : AutoCloseable {

    private val tag = "RemoteSession"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val ready = CompletableDeferred<Boolean>()
    private var agent: JuiceAgent? = null
    private var serverSocket: ServerSocket? = null
    private val connected = AtomicBoolean(false)
    // Per-conn_id channels so each OkHttp socket only sees ITS own reassembled
    // response frames. Sharing one channel across all handleClients caused
    // response bodies to be delivered to the wrong waiter.
    private val incomingByConn = java.util.concurrent.ConcurrentHashMap<Int, Channel<ByteArray>>()
    private val seqCounter = SeqCounter()
    private val reassembler = Reassembler()
    private val nextConnId = java.util.concurrent.atomic.AtomicInteger(1)

    var localPort: Int = 0
        private set

    @Volatile
    var lastPongAt: Long = 0L
        private set
    private val deathTimeout = 30_000L  // ms — session is "dead" if no PONG for 30s
    /** True if a PONG has been received within the last [deathTimeout] ms. */
    val isAlive: Boolean
        get() = lastPongAt > 0 && (System.currentTimeMillis() - lastPongAt) < deathTimeout

    suspend fun connect(): Boolean {
        Log.i(tag, "[$serviceId] connect() begin")
        val gatheredDone = CompletableDeferred<Unit>()
        val stateConnected = CompletableDeferred<Boolean>()

        val listener = object : AgentListener {
            override fun onCandidate(sdp: String) {
                // Trickle ICE not used over /api/match; we batch candidates after gathering done.
            }
            override fun onGatheringDone() {
                Log.i(tag, "[$serviceId] gathering done")
                gatheredDone.complete(Unit)
            }
            override fun onStateChanged(state: Int) {
                Log.i(tag, "[$serviceId] state=$state")
                when (state) {
                    3, 4 -> if (!stateConnected.isCompleted) stateConnected.complete(true)
                    5    -> if (!stateConnected.isCompleted) stateConnected.complete(false)
                }
            }
            override fun onRecv(data: ByteArray) {
                val hdr = parseHeader(data)
                // Heartbeat PONG (conn=0, seq=2, total=0, offset=0): mark alive.
                if (hdr != null && hdr.connId == 0 && hdr.totalSize == 0 && hdr.offset == 0) {
                    lastPongAt = System.currentTimeMillis()
                    return
                }
                Log.i(tag, "[$serviceId] onRecv ${data.size}B hdr=conn=${hdr?.connId} seq=${hdr?.seq} total=${hdr?.totalSize} off=${hdr?.offset}")
                val done = reassembler.feed(data)
                if (done != null) {
                    val preview = done.second.take(120)
                        .joinToString("") { b -> if (b.toInt() in 32..126) "${b.toInt().toChar()}" else "." }
                    Log.i(tag, "[$serviceId] reassembled frame conn=${done.first} bytes=${done.second.size} ascii='${preview}'")
                    val ch = incomingByConn[done.first]
                    if (ch != null) ch.trySend(done.second)
                    else Log.w(tag, "[$serviceId] no handleClient for conn=${done.first} — dropped ${done.second.size} bytes")
                }
            }
        }

        // STUN for srflx + TURN relay for CGNAT/5G networks.
        // TURN is on the signaling server (same host, already reachable from any network).
        val a = JuiceAgent(
            listener = listener,
            stunHost = "stun.l.google.com", stunPort = 19302,
            turnHost = "142.93.223.221", turnPort = 3478,
            turnUser = "p2pturn", turnPass = "642e420232d18e546a911956232e0666",
        )
        agent = a
        a.gatherCandidates()

        // STUN responds in <1 s; TURN relay allocation can take 3-5 s. Allow 8 s so
        // the relay candidate (needed on CGNAT/5G networks) makes it into the SDP.
        val gathered = withTimeoutOrNull(8_000) { gatheredDone.await() } != null
        if (!gathered) {
            Log.w(tag, "[$serviceId] gathering timeout — proceeding with available candidates")
        }

        val localSdp = a.localDescription
        Log.i(tag, "[$serviceId] localSdp:\n${localSdp.take(500)}")

        val sig = SignalingClient(
            host = cfg.signalingHost,
            port = cfg.signalingPort,
        )
        // consumer_api.c passes the full juice local description (which already
        // contains the a=candidate lines) as `consumer_sdp` — so we do the same.
        val resp = withContext(Dispatchers.IO) {
            sig.match(serviceId, localSdp, emptyList())
        }
        if (!resp.ok) {
            Log.e(tag, "[$serviceId] match failed raw=${resp.raw.take(200)}")
            return finishConnect(false)
        }
        Log.i(tag, "[$serviceId] match ok provSdp[0..200]=${resp.providerSdp.take(200)} cands=${resp.candidates.size}")

        // If the provider SDP has only host candidates (STUN gathering not yet
        // complete on the NVR side), synthesize an srflx candidate from the
        // provider_ip field.  The NVR uses port-preserving NAT so the public
        // address is provider_ip:same_port as the host candidate.
        val remoteSdp = if (!resp.providerSdp.contains("typ srflx") && resp.providerIp.isNotBlank()) {
            val hostLine = resp.providerSdp.lineSequence().firstOrNull { it.contains("typ host") }
            val hostPort = hostLine?.let {
                Regex("(\\d+) typ host").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull()
            }
            if (hostPort != null) {
                val syntheticSrflx = "a=candidate:99 1 UDP 1678769919 ${resp.providerIp} $hostPort typ srflx raddr 0.0.0.0 rport 0"
                Log.i(tag, "[$serviceId] injecting synthetic srflx: $syntheticSrflx")
                resp.providerSdp + "\n$syntheticSrflx"
            } else resp.providerSdp
        } else resp.providerSdp

        a.remoteDescription = remoteSdp
        a.setRemoteGatheringDone()

        val ok = withTimeoutOrNull(15_000) { stateConnected.await() } ?: false
        Log.i(tag, "[$serviceId] connected=$ok")
        if (!ok) return finishConnect(false)
        connected.set(true)

        // Send an application-level PING (conn_id=0, seq=1, total=0, offset=0)
        // to confirm bidirectional path BEFORE we expose the local TCP
        // listener. Matches consumer_api.c's keepalive_thread_func warm-up.
        lastPongAt = System.currentTimeMillis()  // seed; refreshed by PONG
        sendHeartbeat()

        // Start local TCP listener on an ephemeral port.
        val srv = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        serverSocket = srv
        localPort = srv.localPort
        Log.i(tag, "[$serviceId] local TCP listener on 127.0.0.1:$localPort")

        scope.launch { acceptLoop(srv) }
        // Periodic PING every 10s so the per-conn keepalive window stays open.
        scope.launch {
            while (scope.isActive && connected.get()) {
                delay(10_000)
                sendHeartbeat()
            }
        }
        return finishConnect(true)
    }

    private fun finishConnect(result: Boolean): Boolean {
        if (!ready.isCompleted) ready.complete(result)
        return result
    }

    private suspend fun acceptLoop(srv: ServerSocket) {
        try {
            while (scope.isActive) {
                val client = withContext(Dispatchers.IO) { srv.accept() }
                Log.i(tag, "[$serviceId] tcp accept ${client.remoteSocketAddress}")
                scope.launch { handleClient(client) }
            }
        } catch (t: Throwable) {
            if (scope.isActive) Log.w(tag, "[$serviceId] accept loop error: ${t.message}")
        }
    }

    private suspend fun handleClient(client: Socket) {
        val connId = nextConnId.getAndIncrement()
        val myInbox = Channel<ByteArray>(capacity = Channel.UNLIMITED)
        incomingByConn[connId] = myInbox
        Log.i(tag, "[$serviceId] handleClient conn_id=$connId begin")
        try {
            val out = client.getOutputStream()
            val inp = client.getInputStream()

            // Reverse direction: assembled frames from THIS conn_id → TCP client.
            val drainJob = scope.launch {
                while (scope.isActive && !client.isClosed) {
                    val data = myInbox.receiveCatching().getOrNull() ?: break
                    try {
                        out.write(data); out.flush()
                    } catch (_: Throwable) { break }
                }
            }

            // Forward direction: bytes from local TCP client → chunked over libjuice.
            val buf = ByteArray(64 * 1024)
            while (scope.isActive && !client.isClosed) {
                val n = withContext(Dispatchers.IO) { inp.read(buf) }
                if (n <= 0) break
                val ok = sendChunked(connId, buf, n)
                if (!ok) {
                    Log.w(tag, "[$serviceId] juice_send failed mid-chunking — closing")
                    break
                }
            }
            drainJob.cancel()
        } catch (t: Throwable) {
            Log.w(tag, "[$serviceId] handleClient error: ${t.message}")
        } finally {
            incomingByConn.remove(connId)
            runCatching { myInbox.close() }
            runCatching { client.close() }
        }
    }

    /** Application-level PING: conn_id=0, seq=1, total_size=0, offset=0. */
    private fun sendHeartbeat() {
        val pkt = buildChunk(0, 1, 0, 0, ByteArray(0), 0, 0)
        val rc = agent?.send(pkt) ?: -1
        Log.i(tag, "[$serviceId] sendHeartbeat rc=$rc")
    }

    /** Split [length] bytes from [src] into MAX_CHUNK-byte chunks, prefix each
     *  with the 16-byte header, and juice_send them. Returns false on failure. */
    private fun sendChunked(connId: Int, src: ByteArray, length: Int): Boolean {
        var offset = 0
        var idx = 0
        while (offset < length) {
            val chunkLen = minOf(ChunkProtocol.MAX_CHUNK, length - offset)
            val seq = seqCounter.next()
            val pkt = buildChunk(connId, seq, length, offset, src, offset, chunkLen)
            val rc = agent?.send(pkt) ?: -1
            val preview = src.sliceArray(offset until offset + chunkLen).take(120)
                .joinToString("") { b -> if (b.toInt() in 32..126) "${b.toInt().toChar()}" else "." }
            Log.i(tag, "[$serviceId] send chunk #$idx conn=$connId seq=$seq total=$length off=$offset len=$chunkLen rc=$rc ascii='${preview}'")
            if (rc < 0) return false
            offset += chunkLen; idx++
        }
        return true
    }

    private fun extractCandidates(sdp: String): List<String> =
        sdp.lineSequence()
            .filter { it.trim().startsWith("a=candidate:") }
            .map { it.trim().removePrefix("a=") }
            .toList()

    private fun stripCandidates(sdp: String): String =
        sdp.lineSequence()
            .filterNot { it.trim().startsWith("a=candidate:") }
            .joinToString("\r\n")

    override fun close() {
        scope.cancel()
        runCatching { serverSocket?.close() }
        runCatching { agent?.close() }
        agent = null
        serverSocket = null
    }
}

/** Remote-mode configuration the user enters once (or has saved). */
data class RemoteConfig(
    val signalingHost: String = "142.93.223.221",
    val signalingPort: Int = 8888,
    val apiToken: String     = "p2p-server-api-token-change-me",
    val turnHost: String     = "turn.devices.arcisai.io",
    val turnPort: Int        = 3478,
    val turnUser: String     = "arcisai",
    val turnPass: String     = "turnpassword123",
)
