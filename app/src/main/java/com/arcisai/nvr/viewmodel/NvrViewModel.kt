package com.arcisai.nvr.viewmodel

import android.app.Application
import android.content.ContentValues
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.arcisai.nvr.data.ChannelCache
import com.arcisai.nvr.data.CredentialStore
import com.arcisai.nvr.data.NvrCredentials
import com.arcisai.nvr.net.AESEncryption
import com.arcisai.nvr.net.AbdDto
import com.arcisai.nvr.net.AddAbdRequest
import com.arcisai.nvr.net.BackendApi
import com.arcisai.nvr.net.GetAbdRequest
import com.arcisai.nvr.net.LoginRequest
import com.arcisai.nvr.net.NetSdkApi
import com.arcisai.nvr.net.NetSdkException
import com.arcisai.nvr.net.OnvifDiscovery
import com.arcisai.nvr.net.OnvifResolver
import com.arcisai.nvr.net.PublisherApi
import com.arcisai.nvr.net.PtzClient
import com.arcisai.nvr.net.RtspTlsProxy
import com.arcisai.nvr.net.SubnetSweep
import com.arcisai.nvr.net.CameraWsChatClient
import com.arcisai.nvr.net.WsReplayClient
import com.arcisai.nvr.p2p.RemoteConfig
import com.arcisai.nvr.p2p.RemoteSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

data class ChannelInfo(
    val id: Int,
    val ipAddr: String,
    val port: Int,
    val username: String,
    val modelName: String,
    val protocol: String,   // N1 / HIKVISION / DAHUA / ONVIF / RTSP
    val enabled: Boolean,
)

data class LoginEvent(val epochMs: Long, val email: String)

class NvrViewModel(app: Application) : AndroidViewModel(app) {
    private val store = CredentialStore(app)
    private val cache = ChannelCache(app)
    private val audioPrefs = app.getSharedPreferences("channel_audio_gain", android.content.Context.MODE_PRIVATE)
    // Lazy: created on first cloud login.
    private val cloudApi: BackendApi by lazy { BackendApi.create(app) }

    var credentials by mutableStateOf<NvrCredentials?>(null)
        private set
    var api by mutableStateOf<NetSdkApi?>(null)
        private set

    // Login screen state
    var loginStatus by mutableStateOf<String?>(null)
    var loginBusy by mutableStateOf(false)

    // ---- Arcis cloud (Remote-mode account) state -------------------------
    /** Whether the user has a usable cookie session against dev.arcisai.io. */
    var accountSignedIn by mutableStateOf(false)
        private set
    /** Friendly name + email of the currently signed-in cloud user. */
    var accountName  by mutableStateOf<String?>(null)
        private set
    var accountEmail by mutableStateOf<String?>(null)
        private set
    /** Login events recorded in this app session (newest-first after reversal). */
    var loginActivity by mutableStateOf<List<LoginEvent>>(emptyList())
        private set
    /** The list of ABDs (NVRs) the user owns, last-fetched. */
    var myAbds by mutableStateOf<List<AbdDto>>(emptyList())
        private set
    var abdListLoading by mutableStateOf(false)
        private set
    var abdListError by mutableStateOf<String?>(null)
        private set
    /** deviceIds we've actually reached over P2P this app session. The cloud
     *  backend's `status` is often stale ("offline" even when the NVR's P2P
     *  provider is live), so the My-NVRs list also treats a device as online
     *  if we've connected to it — i.e. "if it connects, it's online". */
    var sessionOnlineIds by mutableStateOf<Set<String>>(emptySet())
        private set
    /** Status message shown on Login + My-NVRs screens (snackbar-style). */
    var accountStatus by mutableStateOf<String?>(null)

    /** Destination the app should open on cold start.
     *  Null while the session-restore check is in flight (show a loading screen). */
    var startDestination by mutableStateOf<String?>(null)
        private set

    // Channels list (Home screen)
    var channels by mutableStateOf<List<ChannelInfo>>(emptyList())
        private set
    var channelsError by mutableStateOf<String?>(null)
    var channelsLoading by mutableStateOf(false)

    // Remote P2P sessions — one per service_id (NVR HTTP + each channel RTSP).
    private val sessions = ConcurrentHashMap<String, RemoteSession>()
    private val remoteConfig = RemoteConfig()
    var remoteStatus by mutableStateOf<String?>(null)

    // Pre-warm: full P2P sessions started in the background as soon as the NVR
    // list is shown, so by the time the user taps a device ICE is often done.
    private val prewarmSessions = ConcurrentHashMap<String, RemoteSession>()
    private val prewarmJobs    = ConcurrentHashMap<String, Job>()

    // These caches are used inside `attemptReconnectMain` which can run during init
    // (Dispatchers.Main.immediate runs inline), so they must be declared BEFORE the
    // init blocks to guarantee they are initialized when first accessed.
    private val onvifUrlCache  = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val streamUrlCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Called from MyNvrsScreen when the device list loads.
     *  Quietly connects to up to 4 NVRs so [login] can skip ICE entirely. */
    fun prewarmP2p(deviceIds: List<String>) {
        deviceIds.take(4).forEach { deviceId ->
            if (prewarmSessions.containsKey(deviceId) || prewarmJobs.containsKey(deviceId)) return@forEach
            val job = viewModelScope.launch(Dispatchers.IO) {
                val s = RemoteSession(deviceId, remoteConfig)
                val ok = s.connect()
                if (ok) {
                    if (prewarmSessions.putIfAbsent(deviceId, s) != null) {
                        s.close()
                    } else {
                        withContext(Dispatchers.Main) {
                            sessionOnlineIds = sessionOnlineIds + deviceId
                        }
                    }
                } else {
                    s.close()
                }
                prewarmJobs.remove(deviceId)
            }
            prewarmJobs[deviceId] = job
        }
    }

    private fun cancelPrewarm() {
        prewarmJobs.values.forEach { it.cancel() }
        prewarmJobs.clear()
        prewarmSessions.values.forEach { runCatching { it.close() } }
        prewarmSessions.clear()
    }

    /** True if the main HTTP-API P2P session has heard a PONG recently. UI uses
     *  this to surface "NVR offline" banners + suppress error toasts during
     *  transient outages. Always true in LAN mode (no session to check). */
    var nvrOnline by mutableStateOf(true)
        private set

    init {
        // Poll the main HTTP session's liveness every 5s. Switches the
        // `nvrOnline` flag when PONGs stop arriving (NVR offline or P2P died),
        // and auto-reopens the session when the NVR comes back, so the user
        // never has to log in again after a transient outage.
        viewModelScope.launch {
            while (true) {
                val creds = credentials
                if (creds?.remote == true) {
                    val s = sessions[creds.deviceId]
                    val alive = s?.isAlive == true
                    nvrOnline = alive
                    if (!alive) {
                        attemptReconnectMain(creds)
                    }
                } else {
                    nvrOnline = true
                }
                kotlinx.coroutines.delay(5_000)
            }
        }
    }

    private val reconnecting = java.util.concurrent.atomic.AtomicBoolean(false)
    private suspend fun attemptReconnectMain(creds: NvrCredentials) {
        if (!reconnecting.compareAndSet(false, true)) return
        try {
            android.util.Log.i("NvrViewModel", "attemptReconnectMain: starting for ${creds.deviceId}")
            sessions.remove(creds.deviceId)?.let { runCatching { it.close() } }
            val perChan = sessions.keys.filter { it.startsWith("${creds.deviceId}-") }
            for (k in perChan) sessions.remove(k)?.let { runCatching { it.close() } }
            // Tear down the streaming-side tunnels too so live/playback resume
            // cleanly after the NVR comes back. They self-heal lazily on the next
            // stream fetch (isAlive check), but a dead-but-not-yet-reaped tunnel
            // would otherwise serve one stalled request first.
            publisherTunnel?.let { runCatching { it.close() } }; publisherTunnel = null
            replayTunnel?.let { runCatching { it.close() } }; replayTunnel = null
            streamUrlCache.clear()  // ports change when tunnels rebuild

            // Bail if the user logged out / switched NVR before we got here —
            // otherwise we'd resurrect a P2P session the user just tore down
            // (leaving it "active" on the signaling server and blocking re-login).
            if (credentials?.deviceId != creds.deviceId) {
                android.util.Log.i("NvrViewModel", "attemptReconnectMain: aborted — creds changed")
                return
            }
            val ns = RemoteSession(creds.deviceId, remoteConfig)
            val ok = ns.connect()
            if (!ok) {
                ns.close()
                android.util.Log.w("NvrViewModel", "attemptReconnectMain: connect() returned false")
                return
            }
            // Re-check after the (slow) connect: logout may have happened during it.
            if (credentials?.deviceId != creds.deviceId) {
                android.util.Log.i("NvrViewModel", "attemptReconnectMain: discarding — logged out during connect")
                ns.close()
                return
            }
            sessions[creds.deviceId] = ns
            val tunnelCreds = creds.copy(host = "127.0.0.1", port = ns.localPort)
            api = NetSdkApi(tunnelCreds)
            android.util.Log.i("NvrViewModel", "attemptReconnectMain: ok, new port=${ns.localPort}")
            nvrOnline = true
            reconnectToken++  // replay tunnel was torn down → talkback endpoint needs refresh
            // Pre-warm pub + RTSP + replay tunnels in parallel so LiveScreen finds them ready
            // the moment streamRefreshToken fires. Without this, each ICE handshake would
            // happen sequentially inside ensureChannelStreamUrl, adding 5-15 s per tunnel.
            viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val ch = selectedLiveChannel
                    kotlinx.coroutines.coroutineScope {
                        launch { ensurePublisherTunnel() }
                        launch { ensureChannelRtspTunnel(ch) }
                        launch { replayEndpoint() }
                        launch { ensureChannelHttpTunnel(ch) }
                    }
                } finally {
                    streamRefreshToken++  // tunnels ready → tell LiveScreen to reload VLC
                }
            }
            refreshChannels()
            loadIpCamInfo()
            loadConnectedChannels()
        } catch (t: Throwable) {
            android.util.Log.e("NvrViewModel", "attemptReconnectMain failed: ${t.message}", t)
        } finally {
            reconnecting.set(false)
        }
    }

    /** Clear the cached stream URL for [channelId] so the next
     *  [ensureChannelStreamUrl] call re-resolves it from the publisher. */
    fun clearStreamUrlCache(channelId: Int) {
        streamUrlCache.remove("$channelId-0")
        streamUrlCache.remove("$channelId-1")
        onvifUrlCache.remove("$channelId-0")
        onvifUrlCache.remove("$channelId-1")
    }

    /** Manual "Retry now" — kicks an immediate reconnect attempt off the 5 s
     *  polling cadence (e.g. from the offline banner's Retry button). No-op in
     *  LAN mode or when already reconnecting (guarded inside). */
    fun reconnectNow() {
        val creds = credentials ?: return
        if (!creds.remote) return
        viewModelScope.launch { attemptReconnectMain(creds) }
    }

    init {
        // Restore last-known channels + ipCamInfo from disk so the UI never
        // flashes "no cameras attached" while a fresh fetch is in flight.
        runCatching {
            cache.loadIpCamInfoJson()?.let { js ->
                val arr = JSONArray(js)
                ipCamInfo = padIpCamInfo(arr, maxChannels)
                channels = padChannels(parseIpCamInfo(arr), maxChannels)
            }
        }

        // Auto-restore the last session so the user is not forced back to the
        // login screen every time the app is opened (they stay logged in until
        // they explicitly tap Logout).
        viewModelScope.launch {
            // Always guarantee startDestination is set — an unhandled exception
            // here would leave it null and the loading screen would never navigate.
            try {
                val saved = store.load()
                if (saved == null) {
                    startDestination = "login"
                    return@launch
                }
                if (!saved.remote) {
                    // LAN: credentials are enough — no network call needed for navigation.
                    credentials = saved
                    api = NetSdkApi(saved)
                    startDestination = "main"
                } else {
                    // Cloud: check the persisted cookie — no network round-trip needed.
                    // Accessing cloudApi initialises PersistentCookieStore from SharedPreferences.
                    @Suppress("UNUSED_EXPRESSION") cloudApi
                    val cookieJar = BackendApi.cookieJarInstance
                    if (cookieJar != null && cookieJar.hasSessionFor(BackendApi.HOST)) {
                        accountSignedIn = true
                        accountEmail = saved.accountEmail.ifBlank { null }
                        accountName  = saved.accountName.ifBlank { null }
                        if (saved.deviceId.isNotBlank()) {
                            credentials = saved
                            viewModelScope.launch { attemptReconnectMain(saved) }
                            startDestination = "main"
                        } else {
                            startDestination = "my_nvrs"
                        }
                        loadAbds()  // populate NVR list in background
                    } else {
                        startDestination = "login"
                    }
                }
            } catch (_: Throwable) {
                startDestination = "login"
            }
        }
    }

    fun login(creds: NvrCredentials, onSuccess: () -> Unit) {
        if (loginBusy) return
        loginBusy = true
        loginStatus = null
        viewModelScope.launch {
            try {
                if (creds.remote) {
                    // Fast path: use a pre-warmed session if one is ready.
                    // If prewarm is still in flight, wait for it rather than
                    // racing a second connect() against it (two simultaneous
                    // ICE agents for the same service interfere with each other).
                    val prewarmJob = prewarmJobs.remove(creds.deviceId)
                    if (prewarmJob != null) prewarmJob.join()
                    val prewarmed = prewarmSessions.remove(creds.deviceId)
                    if (prewarmed?.isAlive == true) {
                        try {
                            remoteStatus = "Connecting to NVR…"
                            val a = NetSdkApi(creds.copy(host = "127.0.0.1", port = prewarmed.localPort))
                            a.network()  // sanity probe through the pre-warmed tunnel
                            sessions[creds.deviceId] = prewarmed
                            store.save(creds)
                            credentials = creds
                            api = a
                            sessionOnlineIds = sessionOnlineIds + creds.deviceId
                            remoteStatus = "Connected"
                            loadOrdinary()
                            onSuccess()
                            return@launch
                        } catch (t: Throwable) {
                            android.util.Log.w("NvrViewModel", "prewarm probe failed: ${t.message}")
                            prewarmed.close()
                        }
                    } else {
                        prewarmed?.close()
                    }

                    // Slow path: connect fresh. Fast fixed retry (no exponential back-off).
                    var connected = false
                    for (attempt in 1..3) {
                        remoteStatus = if (attempt == 1) "Connecting to NVR…"
                                       else "Reconnecting… ($attempt/3)"
                        val session = RemoteSession(creds.deviceId, remoteConfig)
                        if (!session.connect()) {
                            session.close()
                        } else {
                            try {
                                val a = NetSdkApi(creds.copy(host = "127.0.0.1", port = session.localPort))
                                a.network()  // sanity probe through the tunnel
                                sessions[creds.deviceId] = session
                                store.save(creds)
                                credentials = creds
                                api = a
                                sessionOnlineIds = sessionOnlineIds + creds.deviceId
                                remoteStatus = "Connected"
                                loadOrdinary()
                                onSuccess()
                                connected = true
                                break
                            } catch (t: Throwable) {
                                android.util.Log.w("NvrViewModel", "tunnel probe failed: ${t.message}")
                                session.close()  // free the consumer so the retry is clean
                            }
                        }
                        if (attempt < 3) kotlinx.coroutines.delay(500L)  // fast retry (was 2500×attempt)
                    }
                    if (!connected) {
                        remoteStatus = null
                        loginStatus = "NVR is offline or unreachable"
                    }
                } else {
                    val a = NetSdkApi(creds)
                    a.network()
                    store.save(creds)
                    credentials = creds
                    api = a
                    loadOrdinary()
                    loadIpCamInfo()          // repopulate channels cleared on logout
                    loadConnectedChannels()  // restore online/offline status
                    onSuccess()
                }
            } catch (t: Throwable) {
                loginStatus = t.message ?: "Login failed"
            } finally {
                loginBusy = false
            }
        }
    }

    fun logout() {
        releaseSelectedNvrLocal()

        // Clear cloud session too (best-effort GET /auth/logout + drop cookie).
        viewModelScope.launch {
            runCatching { cloudApi.logout() }
            BackendApi.cookieJarInstance?.clear()
        }
        accountSignedIn = false
        accountName = null
        accountEmail = null
        myAbds = emptyList()
        abdListError = null
        sessionOnlineIds = emptySet()
        startDestination = "login"
    }

    /**
     * Drop the currently-selected NVR (close P2P tunnels, forget creds,
     * clear cached channel data) but keep the cloud account session so the
     * UI can return to MyNvrsScreen and pick a different ABD.
     */
    fun releaseSelectedNvr() {
        releaseSelectedNvrLocal()
    }

    private fun releaseSelectedNvrLocal() {
        sessions.values.forEach { runCatching { it.close() } }
        sessions.clear()
        cancelPrewarm()
        tlsProxies.values.forEach { runCatching { it.close() } }
        tlsProxies.clear()
        publisherTunnel?.let { runCatching { it.close() } }
        publisherTunnel = null
        publisherApiInstance = null
        replayTunnel?.let { runCatching { it.close() } }
        replayTunnel = null
        onvifUrlCache.clear()
        streamUrlCache.clear()
        store.clear()
        cache.clear()
        credentials = null
        api = null
        channels = emptyList()
        ipCamInfo = null
        remoteStatus = null
        discovered.clear()
        discoveryMisses.clear()
        searchResults = null
        connectedChannels = null
        channelStatus = emptyMap()
        lastConnectedMs = 0L
    }

    // ---- Arcis cloud (Remote-mode account flow) --------------------------
    /** Login against dev.arcisai.io. On success the HTTP-only `token` cookie
     *  is auto-persisted by [PersistentCookieStore] so the session survives
     *  app restart; `onSuccess` is called so the UI can route to MyNvrsScreen. */
    fun accountLogin(email: String, password: String, onSuccess: () -> Unit) {
        if (loginBusy) return
        loginBusy = true
        loginStatus = null
        viewModelScope.launch {
            try {
                // Backend AES-CBC-decrypts the password with a shared key/IV
                // (see AESEncryption + Arcis_Main_Backend/authController.js).
                // Sending plaintext fails the length-24 sanity check inside
                // decryptPassword and lands us on the "wrong password" branch.
                val enc = AESEncryption.encrypt(password)
                val resp = cloudApi.login(LoginRequest(email = email, password = enc))
                if (!resp.success) {
                    loginStatus = resp.data ?: resp.message ?: "Login failed"
                    return@launch
                }
                accountSignedIn = true
                accountName  = resp.name
                accountEmail = resp.email ?: email
                // Persist a cloud-session marker so the restore path on next launch
                // knows to check the cookie rather than forcing a fresh login.
                store.save(NvrCredentials(
                    host = "", port = 80, username = "", password = "",
                    remote = true, deviceId = "",
                    accountEmail = resp.email ?: email,
                    accountName  = resp.name ?: "",
                    accountAbdName = "",
                ))
                loginActivity = loginActivity + LoginEvent(
                    epochMs = System.currentTimeMillis(),
                    email   = resp.email ?: email,
                )
                onSuccess()
            } catch (t: Throwable) {
                loginStatus = friendlyHttpError(t)
            } finally {
                loginBusy = false
            }
        }
    }

    /** Try to resume an existing cloud session by hitting /abd/getAbd with the
     *  persisted cookie. If it succeeds, we know the cookie is still valid and
     *  can skip the email/password form on app launch. */
    fun resumeAccountSessionIfAny(onResumed: () -> Unit) {
        viewModelScope.launch {
            try {
                val resp = cloudApi.getAbd()
                if (resp.success) {
                    accountSignedIn = true
                    myAbds = resp.data
                    onResumed()
                }
            } catch (_: Throwable) {
                // No valid session — user has to log in again.
            }
        }
    }

    fun loadAbds() {
        if (abdListLoading) return
        abdListLoading = true
        abdListError = null
        viewModelScope.launch {
            try {
                val resp = cloudApi.getAbd()
                if (!resp.success) {
                    abdListError = resp.message ?: "Couldn't load NVR list"
                    return@launch
                }
                myAbds = resp.data
            } catch (t: Throwable) {
                abdListError = friendlyHttpError(t)
            } finally {
                abdListLoading = false
            }
        }
    }

    /** POST /api/abd/addAbd. Backend validates against EMS so a bad deviceId
     *  comes back as a 404 with a clear message. */
    fun addAbd(name: String, deviceId: String, onResult: (ok: Boolean, msg: String) -> Unit) {
        viewModelScope.launch {
            try {
                val resp = cloudApi.addAbd(AddAbdRequest(name = name.trim(), deviceId = deviceId.trim()))
                if (resp.success) {
                    loadAbds()
                    onResult(true, resp.message ?: "NVR added")
                } else {
                    onResult(false, resp.message ?: "Failed to add NVR")
                }
            } catch (t: Throwable) {
                onResult(false, friendlyHttpError(t))
            }
        }
    }

    /** User picked an ABD on the MyNvrsScreen — open the P2P tunnel to it and
     *  jump into the main app. Re-uses the existing remote login path. */
    fun selectAbd(abd: AbdDto, onSuccess: () -> Unit) {
        val email = accountEmail ?: ""
        val creds = NvrCredentials(
            host = "",
            port = 80,
            username = "admin",   // NVR-local auth still admin/empty (publisher's basic gate)
            password = "",
            remote = true,
            deviceId = abd.deviceId,
            accountEmail = email,
            accountName  = accountName ?: "",
            accountAbdName = abd.name,
        )
        login(creds, onSuccess)
    }

    /**
     * Surfaces the backend's actual error text instead of guessing from the
     * HTTP status code. The Arcis backend mixes two field names — `data` on
     * auth endpoints, `message` on ABD endpoints — so try both. Only fall back
     * to a generic message when we genuinely can't read the body (network
     * error, garbage response, etc).
     */
    private fun friendlyHttpError(t: Throwable): String {
        if (t is retrofit2.HttpException) {
            val body = runCatching { t.response()?.errorBody()?.string().orEmpty() }
                .getOrDefault("")
            if (body.isNotBlank()) {
                val parsed = runCatching {
                    val j = JSONObject(body)
                    j.optString("message").ifBlank { j.optString("data") }
                }.getOrNull()
                if (!parsed.isNullOrBlank()) return parsed
            }
            return when (t.code()) {
                401 -> "Session expired — please sign in again"
                404 -> "Not found"
                in 500..599 -> "Server error (${t.code()}) — try again later"
                else -> "Request failed (${t.code()})"
            }
        }
        val raw = t.message.orEmpty()
        return when {
            raw.contains("UnknownHostException") || raw.contains("ConnectException") ->
                "Couldn't reach dev.arcisai.io — check internet"
            raw.contains("SocketTimeoutException") -> "Connection timed out"
            else -> raw.ifBlank { "Network error" }
        }
    }

    // Per-channel TLS-strip proxies for cameras whose RTSP is TLS-only
    // (rtsps://) — libVLC's Android build doesn't speak rtsps directly.
    // Lazy-opened on first stream, kept until logout / channel rebind.
    private val tlsProxies = ConcurrentHashMap<Int, RtspTlsProxy>()

    /** Cached publisher (:8080) tunnel session in Remote mode. service_id
     *  is `<deviceId>-pub` and the publisher config on the NVR exposes
     *  :8080 via libjuice (P2P_PORT=9109). One session shared for all
     *  /api/channels and /ptz calls. */
    @Volatile private var publisherTunnel: RemoteSession? = null

    /** Lazy PublisherApi — rebuilt on credential change. Resolves base URL
     *  on every request: LAN → http://<host>:8080; Remote → opens publisher
     *  tunnel + http://127.0.0.1:<localPort>. */
    @Volatile private var publisherApiInstance: PublisherApi? = null

    private fun publisher(): PublisherApi? {
        val creds = credentials ?: return null
        publisherApiInstance?.let { return it }
        val api = PublisherApi(creds) { baseUrlForPublisher() }
        publisherApiInstance = api
        return api
    }

    private suspend fun baseUrlForPublisher(): String? {
        val creds = credentials ?: return null
        if (!creds.remote) return "http://${creds.host}:8080"
        val port = ensurePublisherTunnel() ?: return null
        return "http://127.0.0.1:$port"
    }

    /** Open (or reuse) the libjuice tunnel for the publisher's :8080.
     *  Mirrors [ensureChannelHttpTunnel] but bound to a single service_id
     *  (`<deviceId>-pub`) since /api/channels covers all channels. */
    private suspend fun ensurePublisherTunnel(): Int? {
        val creds = credentials ?: return null
        if (!creds.remote) return null
        val cur = publisherTunnel
        if (cur != null && cur.isAlive) return cur.localPort
        cur?.let { runCatching { it.close() } }
        publisherTunnel = null

        val sid = "${creds.deviceId}-pub"
        android.util.Log.i("NvrVM-Pub", "opening publisher tunnel $sid")
        val ns = RemoteSession(sid, remoteConfig)
        if (!ns.connect()) {
            ns.close()
            android.util.Log.w("NvrVM-Pub", "publisher tunnel connect failed for $sid")
            return null
        }
        publisherTunnel = ns
        android.util.Log.i("NvrVM-Pub", "publisher tunnel ready: $sid -> 127.0.0.1:${ns.localPort}")
        return ns.localPort
    }

    /**
     * RTSP URL the libVLC player should open for a given channel.
     *
     * The publisher on the NVR's :8080 owns all per-brand camera knowledge:
     * for each channel it runs ONVIF GetStreamUri (CP Plus, TrueView,
     * Hikvision ONVIF), HTTP Digest / WS-UsernameToken auth, TLS detection
     * (rtsps://), and the N1/HICHIP /ch0_M.264 fallback. The app just
     * fetches /api/channels/<n>/stream and gets back the resolved URL.
     *
     * Three transforms still applied here on the app side:
     *  1. GET /api/channels/<n>/stream — over LAN (:8080 direct) or via
     *     the publisher libjuice tunnel in Remote mode. URL comes back
     *     with creds pre-injected and rtsps:// applied where needed.
     *  2. Remote mode: the URL still references the camera's LAN IP, so
     *     rewrite its host:port to the per-channel RTSP libjuice tunnel
     *     (NVR's tcpsvd 5540+N → camera:554).
     *  3. rtsps:// → spin up a [RtspTlsProxy] on 127.0.0.1 so libVLC's
     *     Android build (no native rtsps) can consume it.
     *
     * `stream` follows the publisher's convention: 1=sub, 0=main.
     */
    // onvifUrlCache and streamUrlCache are declared near the top of the class
    // (before the init blocks) to prevent NPE from Dispatchers.Main.immediate
    // running attemptReconnectMain inline during class initialization.

    suspend fun ensureChannelStreamUrl(channelId: Int, stream: Int = 1): String? {
        val creds = credentials ?: return null
        val streamType = if (stream == 0) "main" else "sub"
        val cacheKey = "$channelId-$stream"

        // Return cached URL when the underlying sessions are still alive,
        // skipping the publisher HTTP round-trip on live-page re-entry.
        streamUrlCache[cacheKey]?.let { cached ->
            val valid = !creds.remote ||
                (sessions["${creds.deviceId}-c$channelId"]?.isAlive == true &&
                 publisherTunnel?.isAlive == true)
            if (valid) return cached
            streamUrlCache.remove(cacheKey)
        }

        val api = publisher() ?: return null

        val resolved = try {
            api.channelStream(channelId, streamType)
        } catch (t: Throwable) {
            android.util.Log.w("NvrViewModel",
                "publisher /api/channels/$channelId/stream failed: ${t.message}")
            return null
        }
        if (resolved.url.isBlank()) {
            android.util.Log.w("NvrViewModel",
                "publisher returned empty URL for ch$channelId (${resolved.error})")
            return null
        }

        val routed = if (creds.remote) {
            val rtspPort = ensureChannelRtspTunnel(channelId) ?: return null
            rewriteUrlHost(resolved.url, "127.0.0.1", rtspPort)
        } else {
            // LAN URL resolution order (first non-null wins):
            // 1) ONVIF GetStreamUri — exact path + creds direct from camera.
            // 2) Publisher URL verbatim — the publisher already has the camera's direct
            //    RTSP URL (with credentials). Use it if the camera is TCP-reachable
            //    from the phone (same LAN, no AP client isolation). This avoids
            //    the NVR relay and works even when relay ports aren't all configured.
            // 3) NVR relay rewrite — last resort when camera is not directly reachable
            //    (AP client isolation). Requires tcpsvd relay on 5540+channelId.
            onvifDirectStreamUrl(channelId, stream)
                ?: lanDirectUrl(resolved.url)
                ?: rewriteUrlHost(resolved.url, creds.host, 5540 + channelId)
        }

        android.util.Log.i("NvrViewModel", "streamUrl ch$channelId stream$stream → $routed")
        val result = maybeWrapTls(routed, channelId)
        streamUrlCache[cacheKey] = result
        return result
    }

    /** LAN-only ONVIF self-resolution with session cache. Returns null (→ caller
     *  uses the publisher URL) for non-ONVIF cams, missing IP, or unreachable RTSP. */
    private suspend fun onvifDirectStreamUrl(channelId: Int, stream: Int): String? {
        val cacheKey = "$channelId-$stream"
        onvifUrlCache[cacheKey]?.let { return it }

        val entry = findIpCamEntry(channelId) ?: return null
        if (!entry.optString("Protocolname").equals("ONVIF", ignoreCase = true)) return null
        val ip = entry.optString("IPAddr").ifBlank { return null }
        if (!tcpReachable(ip, 554, 400)) return null
        val url = OnvifResolver.resolveStreamUri(
            ip = ip,
            onvifPort = entry.optInt("Port", 80),
            user = entry.optString("Username", "admin"),
            pass = entry.optString("Password", ""),
            wantSub = stream != 0,
        ) ?: return null
        android.util.Log.i("NvrViewModel", "ONVIF-resolved ch$channelId -> $url")
        onvifUrlCache[cacheKey] = url
        return url
    }

    private suspend fun tcpReachable(host: String, port: Int, timeoutMs: Int): Boolean =
        withContext(Dispatchers.IO) {
            try { java.net.Socket().use { it.connect(java.net.InetSocketAddress(host, port), timeoutMs) }; true }
            catch (_: Throwable) { false }
        }

    /** Returns [url] unchanged if its embedded host:port is TCP-reachable within
     *  600 ms (i.e. the camera is directly accessible on the same LAN).
     *  Returns null otherwise so the caller falls back to the NVR relay. */
    private suspend fun lanDirectUrl(url: String): String? {
        return try {
            val uri = java.net.URI(url)
            val host = uri.host ?: return null
            val port = if (uri.port > 0) uri.port else 554
            if (tcpReachable(host, port, 600)) url else null
        } catch (_: Throwable) { null }
    }

    /** Open (or reuse) the per-channel RTSP libjuice tunnel. Returns its
     *  localhost port. Only valid in Remote mode. Mirrors the HTTP-tunnel
     *  helper so ONVIF + non-ONVIF code paths share one lifecycle. */
    private suspend fun ensureChannelRtspTunnel(channelId: Int): Int? {
        val creds = credentials ?: return null
        if (!creds.remote) return null
        val sid = "${creds.deviceId}-c$channelId"
        val existing = sessions[sid]
        if (existing != null && existing.isAlive) return existing.localPort
        existing?.let { runCatching { it.close() } }
        sessions.remove(sid)

        val ns = RemoteSession(sid, remoteConfig)
        val ok = ns.connect()
        if (!ok) { ns.close(); return null }
        sessions[sid] = ns
        android.util.Log.i("NvrViewModel", "RTSP tunnel ready: $sid -> 127.0.0.1:${ns.localPort}")
        return ns.localPort
    }

    /** Open (or reuse) a P2P tunnel to the NVR's per-channel HTTP relay port
     *  (8540+N → camera:80). Used to route /cgi-bin/Chat (talkback, siren) over P2P.
     *  Service ID: {deviceId}-h{N} (registered in provider-nvr-multi.conf as ABD-...-hN). */
    private suspend fun ensureChannelHttpTunnel(channelId: Int): Int? {
        val creds = credentials ?: return null
        if (!creds.remote) return null
        val sid = "${creds.deviceId}-h$channelId"
        val existing = sessions[sid]
        if (existing != null && existing.isAlive) return existing.localPort
        existing?.let { runCatching { it.close() } }
        sessions.remove(sid)
        val ns = RemoteSession(sid, remoteConfig)
        val ok = ns.connect()
        if (!ok) { ns.close(); return null }
        sessions[sid] = ns
        android.util.Log.i("NvrViewModel", "HTTP tunnel ready: $sid -> 127.0.0.1:${ns.localPort}")
        return ns.localPort
    }

    /** Swap the host:port of an rtsp/rtsps URL while preserving scheme,
     *  userinfo, path, and query. Used to point an ONVIF-returned camera
     *  URL at the local libjuice tunnel. */
    private fun rewriteUrlHost(url: String, newHost: String, newPort: Int): String {
        val schemeEnd = url.indexOf("://").takeIf { it >= 0 } ?: return url
        val authStart = schemeEnd + 3
        val pathStart = url.indexOf('/', authStart).takeIf { it >= 0 } ?: url.length
        val auth = url.substring(authStart, pathStart)
        val at = auth.lastIndexOf('@')
        val userinfo = if (at >= 0) auth.substring(0, at + 1) else ""
        val tail = url.substring(pathStart)
        return "${url.substring(0, authStart)}${userinfo}${newHost}:${newPort}${tail}"
    }

    /** If the URL is `rtsps://`, ensure a per-channel TLS-strip proxy is
     *  running and rewrite to `rtsp://127.0.0.1:<port>/...`. libVLC's
     *  Android build doesn't speak rtsps — verified live, "only real/helix
     *  rtsp servers supported for now" error.
     *
     *  Reuse rule: only reuse the cached proxy if it still targets the same
     *  upstream host:port. In P2P mode the libjuice tunnel may have been
     *  rebuilt on a different localhost port between stream opens — reusing
     *  the old proxy would dial a dead port and the stream would silently
     *  fail on the 2nd open even though the 1st worked. */
    private fun maybeWrapTls(url: String, channelId: Int): String {
        if (!url.startsWith("rtsps://", ignoreCase = true)) return url
        val hp = RtspTlsProxy.parseCameraHostPort(url) ?: return url
        val existing = tlsProxies[channelId]
        val proxy = if (existing != null && existing.localPort > 0 &&
                        existing.cameraHost == hp.first && existing.cameraPort == hp.second) {
            existing
        } else {
            existing?.let { runCatching { it.close() } }
            RtspTlsProxy(hp.first, hp.second).also {
                it.start()
                tlsProxies[channelId] = it
            }
        }
        val rewritten = RtspTlsProxy.rewriteUrl(url, proxy.localPort)
        android.util.Log.i("NvrViewModel", "rtsps wrap ch$channelId: $url -> $rewritten (proxy target ${proxy.cameraHost}:${proxy.cameraPort})")
        return rewritten
    }

    // Total channel slots reported by the NVR. Default 4 (this firmware's MAX_CHN);
    // updated from /netsdk/Stat/DeviceInfo on login.
    var maxChannels by mutableStateOf(4)
        private set

    // Per-device channel count cache: deviceId → real MAX_CHN (survives screen nav).
    val channelCountCache = mutableStateMapOf<String, Int>()

    fun refreshChannels() {
        val a = api ?: return
        channelsLoading = true
        channelsError = null
        viewModelScope.launch {
            try {
                val arr = a.ipCamInfo()
                ipCamInfo = padIpCamInfo(arr, maxChannels)
                channels = padChannels(parseIpCamInfo(arr), maxChannels)
                cache.saveIpCamInfo(arr.toString())
                android.util.Log.i("NvrViewModel",
                    "refreshChannels: parsed=${channels.size} (padded to $maxChannels)")
            } catch (t: Throwable) {
                channelsError = t.message
                android.util.Log.e("NvrViewModel", "refreshChannels failed: ${t.message}", t)
            } finally {
                channelsLoading = false
            }
        }
    }

    private fun parseIpCamInfo(arr: JSONArray): List<ChannelInfo> {
        val out = mutableListOf<ChannelInfo>()
        for (i in 0 until arr.length()) {
            val c = arr.getJSONObject(i)
            val id = c.optInt("ID", i)
            out += ChannelInfo(
                id        = id,
                ipAddr    = c.optString("IPAddr"),
                port      = c.optInt("Port", 80),
                username  = c.optString("Username", "admin"),
                modelName = c.optString("Modelname"),
                protocol  = c.optString("Protocolname", "N1"),
                enabled   = c.optString("Enable") == "True",
            )
        }
        return out
    }

    private fun padChannels(parsed: List<ChannelInfo>, max: Int): List<ChannelInfo> {
        if (parsed.size >= max) return parsed
        val byId = parsed.associateBy { it.id }
        return (0 until max).map { id ->
            byId[id] ?: ChannelInfo(
                id = id, ipAddr = "", port = 0, username = "",
                modelName = "", protocol = "", enabled = false,
            )
        }
    }

    // ------------------------------------------------------------------
    // Manage / Add Camera — IPCamInfo CRUD
    // ------------------------------------------------------------------
    var ipCamInfo by mutableStateOf<JSONArray?>(null)
        private set
    var ipCamInfoLoading by mutableStateOf(false)
    var ipCamInfoError by mutableStateOf<String?>(null)
    var ipCamInfoStatus by mutableStateOf<String?>(null)

    fun loadIpCamInfo() {
        val a = api ?: return
        ipCamInfoLoading = true
        ipCamInfoError = null
        viewModelScope.launch {
            try {
                val raw = a.ipCamInfo()
                ipCamInfo = padIpCamInfo(raw, maxChannels)
                channels = padChannels(parseIpCamInfo(raw), maxChannels)
                cache.saveIpCamInfo(raw.toString())
                android.util.Log.i("NvrViewModel",
                    "loadIpCamInfo: raw=${raw.length()} padded=${ipCamInfo!!.length()}")
            } catch (t: Throwable) {
                ipCamInfoError = t.message
                android.util.Log.e("NvrViewModel", "loadIpCamInfo failed: ${t.message}", t)
            } finally {
                ipCamInfoLoading = false
            }
        }
    }

    private fun padIpCamInfo(raw: JSONArray, max: Int): JSONArray {
        if (raw.length() >= max) return raw
        val byId = (0 until raw.length())
            .map { raw.getJSONObject(it) }
            .associateBy { it.optInt("ID") }
        val out = JSONArray()
        for (id in 0 until max) {
            out.put(byId[id] ?: JSONObject().apply {
                put("ID", id)
                put("IPAddr", "")
                put("Port", 0)
                put("Protocolname", "")
                put("Username", "admin")
                put("Password", "")
                put("Modelname", "")
                put("Enable", "False")
                put("DevType", "IPCAM")
                put("StreamNum", 0)
            })
        }
        return out
    }

    private fun findIpCamEntry(channelId: Int): JSONObject? {
        val arr = ipCamInfo ?: return null
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            if (obj.optInt("ID") == channelId) return obj
        }
        return null
    }

    /** Popup message shown after a camera add/edit when the camera couldn't be
     *  found at the entered IP (the slot is reverted to empty). Null = no popup. */
    var cameraAddMessage by mutableStateOf<String?>(null)

    fun saveIpCamEntry(channelId: Int, edits: Map<String, Any?>, onDone: () -> Unit = {}) {
        val a = api ?: return
        val arr = ipCamInfo ?: return
        val obj = (0 until arr.length())
            .map { arr.getJSONObject(it) }
            .firstOrNull { it.optInt("ID") == channelId } ?: return
        // Capture the IP this slot had BEFORE the edit, so we can tell whether
        // the user is pointing the channel at a NEW camera (→ verify it connects)
        // vs. just tweaking an existing one (→ never wipe it if it's offline).
        val prevIp = obj.optString("IPAddr")
        val expectedIp = (edits["IPAddr"] as? String)?.trim().orEmpty()
        val pointingAtNewIp = expectedIp.isNotBlank() && expectedIp != prevIp
        val portGuess = (edits["Port"] as? Int)
            ?: (edits["Port"] as? String)?.toIntOrNull() ?: 554
        val isLan = credentials?.remote == false
        ipCamInfoStatus = null
        // Dismiss the dialog immediately — the caller closes it the instant Save
        // is tapped; the work below runs in the background.
        onDone()
        viewModelScope.launch {
            try {
                // Fast pre-check (LAN only): something must answer at the new IP
                // before we write it to the NVR. This stops a dead/typo'd IP from
                // ever being bound — the channel stays exactly as it was and never
                // inherits another channel's stream. Over P2P we can't reach a
                // camera IP directly, so we skip the probe and just save.
                if (pointingAtNewIp && isLan) {
                    ipCamInfoStatus = "Checking $expectedIp…"
                    val reachable = tcpReachable(expectedIp, portGuess, 2000) ||
                        tcpReachable(expectedIp, 554, 1500) ||
                        tcpReachable(expectedIp, 80, 1500)
                    if (!reachable) {
                        ipCamInfoStatus = null
                        cameraAddMessage =
                            "No camera found at $expectedIp.\n\nNothing responded at that address, so " +
                            "channel ${channelId + 1} was left with no camera connected. Check the IP, " +
                            "that the camera is powered on and on this network, then try again."
                        return@launch   // obj NOT mutated; NVR left unchanged
                    }
                }
                edits.forEach { (k, v) -> if (v == null) obj.remove(k) else obj.put(k, v) }
                a.setIpCamInfoOne(channelId, obj)
                ipCamInfoStatus = "Saved channel ${channelId + 1} — connecting…"
                loadIpCamInfo()
                refreshChannels()
                loadConnectedChannels()
                // Give the camera a moment to come up, then refresh the badge.
                kotlinx.coroutines.delay(4000)
                loadConnectedChannels()
                ipCamInfoStatus = "Saved channel ${channelId + 1}."
            } catch (t: Throwable) {
                ipCamInfoStatus = "Save failed: ${t.message}"
            }
        }
    }

    fun rebootIpc(channelId: Int) {
        val a = api ?: return
        viewModelScope.launch {
            try {
                a.rebootIpc(channelId)
                ipCamInfoStatus = "Reboot sent to channel ${channelId + 1}"
            } catch (t: Throwable) {
                ipCamInfoStatus = "Reboot failed: ${t.message}"
            }
        }
    }

    fun toggleImageRollover(channelId: Int, on: Boolean) {
        val a = api ?: return
        viewModelScope.launch {
            try {
                a.imageRollover(channelId, on)
                ipCamInfoStatus = "Image rollover ${if (on) "on" else "off"} for channel ${channelId + 1}"
            } catch (t: Throwable) {
                ipCamInfoStatus = "Rollover failed: ${t.message}"
            }
        }
    }

    var searchResults by mutableStateOf<JSONArray?>(null)
    var searchBusy by mutableStateOf(false)

    /** Session-sticky discovery store. Discovery is flaky — an online camera can
     *  miss a scan round (ONVIF's 4 s deadline, a slow RTSP probe), and the old
     *  code overwrote `searchResults` every scan, so a camera that answered once
     *  then missed the next round would vanish even though it's still online.
     *  Instead we keep every device found this session, refresh the ones seen
     *  again, and only drop a device after it's missed [discoveryExpireRounds]
     *  consecutive scans. Keyed by MAC (or IP when no MAC); insertion-ordered so
     *  the visible list stays stable across rescans. Cleared via
     *  [clearSearchResults] or when the NVR is released. */
    private val discovered = LinkedHashMap<String, JSONObject>()
    private val discoveryMisses = HashMap<String, Int>()
    private val discoveryExpireRounds = 3

    /** Wipe the sticky discovery list (the "Clear" button on the scan results). */
    fun clearSearchResults() {
        discovered.clear()
        discoveryMisses.clear()
        searchResults = null
        ipCamInfoStatus = null
    }

    fun searchIpc() {
        android.util.Log.i("NvrViewModel", "searchIpc() called  api=${api != null}")
        val a = api ?: run {
            ipCamInfoStatus = "Not connected"
            return
        }
        searchBusy = true
        // In remote (P2P) mode the phone may be on a different subnet than the NVR,
        // so ONVIF WS-Discovery and the subnet sweep would scan the wrong network.
        // Only the NVR-side R.SEARCH.Ipc call reaches cameras on the NVR's LAN.
        val isRemote = credentials?.remote == true
        ipCamInfoStatus = if (isRemote) "Scanning cameras on NVR's network…"
                          else "Scanning cameras (N1 + ONVIF + sweep)…"
        viewModelScope.launch {
            try {
                val ctx = getApplication<Application>().applicationContext
                val n1Job = async(Dispatchers.IO) {
                    runCatching { a.searchIpcWithResults() }.getOrElse { JSONArray() }
                }
                // ONVIF WS-Discovery and subnet sweep target the phone's local network.
                // Skip them in remote mode — they'd find cameras the NVR can't reach.
                val onvifJob = if (isRemote) null else async(Dispatchers.IO) {
                    runCatching { OnvifDiscovery.scan(ctx, timeoutMs = 4000) }.getOrElse { emptyList() }
                }
                val sweepJob = if (isRemote) null else async(Dispatchers.IO) {
                    runCatching { SubnetSweep.scan(ctx, perHostTimeoutMs = 600) }.getOrElse { emptyList() }
                }
                val n1 = n1Job.await()
                val onvif = onvifJob?.await() ?: emptyList()
                val sweep = sweepJob?.await() ?: emptyList()

                // Merge by MAC if present, else by IP. Priority: N1 (richest
                // metadata) > ONVIF (vendor + model from Scopes) > sweep
                // (only IP + banner-derived vendor).
                // This round's finds, deduped by MAC (else IP). Priority order
                // (N1 first) means richer metadata wins on key collisions.
                val round = LinkedHashMap<String, JSONObject>()
                fun addOnce(o: JSONObject) {
                    val key = o.optString("Mac").ifBlank { o.optString("IPAddr") }.lowercase()
                    if (key.isNotBlank() && !round.containsKey(key)) round[key] = o
                }
                for (i in 0 until n1.length()) addOnce(n1.getJSONObject(i))
                onvif.forEach(::addOnce)
                sweep.forEach(::addOnce)

                // Merge into the sticky store: refresh anything seen this round,
                // age out anything missed (drop only after N misses), append the
                // genuinely-new — so a one-round miss no longer makes a live
                // camera disappear from the list.
                val keys = LinkedHashSet<String>().apply {
                    addAll(discovered.keys); addAll(round.keys)
                }
                for (k in keys) {
                    val fresh = round[k]
                    if (fresh != null) {
                        discovered[k] = fresh          // refresh metadata
                        discoveryMisses.remove(k)
                    } else {
                        val misses = (discoveryMisses[k] ?: 0) + 1
                        if (misses >= discoveryExpireRounds) {
                            discovered.remove(k); discoveryMisses.remove(k)
                        } else {
                            discoveryMisses[k] = misses
                        }
                    }
                }
                val merged = JSONArray().apply { discovered.values.forEach { put(it) } }
                searchResults = merged
                val n = merged.length()
                val roundN = round.size
                ipCamInfoStatus = if (n == 0) "No cameras found"
                    else "Showing $n camera${if (n == 1) "" else "s"} ($roundN this scan)"
                android.util.Log.i("NvrViewModel",
                    "searchIpc(): round N1=${n1.length()} ONVIF=${onvif.size} sweep=${sweep.size} → sticky=$n (round=$roundN)")
            } catch (t: Throwable) {
                ipCamInfoStatus = "Search failed: ${t.message}"
                android.util.Log.e("NvrViewModel", "searchIpc() failed: ${t.message}", t)
            } finally {
                searchBusy = false
            }
        }
    }

    fun assignToChannel(channelId: Int, found: JSONObject, onDone: () -> Unit = {}) {
        val a = api ?: return
        android.util.Log.i("NvrViewModel", "assignToChannel ch=$channelId found.IP=${found.optString("IPAddr")}")
        val arr = ipCamInfo
        if (arr == null) {
            ipCamInfoStatus = "Channel list not loaded"
            return
        }
        val target = (0 until arr.length())
            .map { arr.getJSONObject(it) }
            .firstOrNull { it.optInt("ID") == channelId }
        if (target == null) {
            ipCamInfoStatus = "No slot ${channelId + 1} on this NVR"
            return
        }
        target.put("IPAddr",       found.optString("IPAddr"))
        target.put("Port",         found.optInt("Port", 80))
        target.put("Protocolname", found.optString("Protocolname", "N1"))
        target.put("Username",     found.optString("Username", "admin"))
        target.put("Password",     found.optString("Password", ""))
        target.put("Modelname",    found.optString("Modelname"))
        target.put("MACAddr",      found.optString("Mac"))
        target.put("StreamNum",    found.optInt("StreamNum", 0))
        target.put("DevType",      found.optString("DevType", "IPCAM"))
        target.put("Enable",       "True")
        target.put("AddType",      "Search")
        listOf("hichip", "N1", "SoftwareVersion", "OdmNum", "SupportHumanDetect",
               "SupportFaceDetect", "SupportPir", "MediaProtocolVer", "InterfaceType")
            .forEach { k -> if (found.has(k)) target.put(k, found.get(k)) }
        ipCamInfoStatus = null
        onDone()   // close the picker immediately; the rest resolves in background
        viewModelScope.launch {
            try {
                a.setIpCamInfoOne(channelId, target)
                ipCamInfoStatus = "Assigned to channel ${channelId + 1} — connecting…"
                loadIpCamInfo()
                refreshChannels()
                loadConnectedChannels()
            } catch (t: Throwable) {
                ipCamInfoStatus = "Assign failed: ${t.message}"
            }
        }
    }

    /** LAN-mode RTSP URL for a channel — used by features that want a URL
     *  without opening a P2P session (snapshots, recordings). */
    fun rtspUrlForChannel(channelId: Int, stream: Int = 1): String? {
        val entry = findIpCamEntry(channelId) ?: return null
        return NetSdkApi.cameraRtspUrl(entry, channelId, stream)
    }

    // ------------------------------------------------------------------
    // Recording playback.
    //
    //  - Search: PUT /netsdk/R.SearchRecord with the firmware envelope
    //    {DEV,VER,API,Parameter}. Channel + Type are arrays of capitalised
    //    booleans ("True"/"False"); BeginTime/EndTime are "HH:MM:SS"; Date is
    //    "YYYY-MM-DD" (verified against the NVR web UI bundle). Response items
    //    carry TimeStart/TimeEnd as epoch SECONDS in the NVR's local wall clock
    //    expressed as if UTC — format with ZoneOffset.UTC, never the device's
    //    zone (see arcis-nvr-record-timestamps).
    //  - Stream: ReplayPlayer over the :10000 replay protocol. LAN dials the
    //    NVR directly; Remote opens a `<deviceId>-replay` libjuice tunnel
    //    (mirrors the publisher tunnel). The device provider must register a
    //    matching service forwarding to 127.0.0.1:10000.
    // ------------------------------------------------------------------
    data class RecordSegment(val channel: Int, val startSec: Long, val endSec: Long, val type: String)

    var recordSegments by mutableStateOf<List<RecordSegment>?>(null)
    var recordSearchBusy by mutableStateOf(false)
    var recordSearchStatus by mutableStateOf<String?>(null)

    /** Per-channel LIVE connection status from `/netsdk/Stat` IPC[].Status —
     *  the authoritative "is this camera actually online" signal (IPCamInfo's
     *  own Status field comes back empty on this firmware). Common values:
     *  "Connect success" (online), "Connect Failed" (offline), "Updating"
     *  (connecting). Drives the online/offline badges on Live, Playback and
     *  Cameras. `connectedChannels` is the online subset. Null/empty = not
     *  loaded yet (callers fall back to the configured Enable flag). */
    var connectedChannels by mutableStateOf<Set<Int>?>(null)
        private set
    var channelStatus by mutableStateOf<Map<Int, String>>(emptyMap())
        private set

    /** Last captured frame for each channel — shown as tile thumbnail on the
     *  Device tab grid. Written from LiveScreen while VLC is playing. */
    val channelThumbnails = mutableStateMapOf<Int, Bitmap>()

    fun setChannelThumbnail(channelId: Int, bmp: Bitmap) {
        channelThumbnails[channelId] = bmp
    }

    /** Channel currently highlighted in the LiveScreen grid — PlaybackTabScreen
     *  reads this to pre-select the same channel on open. */
    var selectedLiveChannel: Int = 0

    /** Set by EventsTabScreen before navigating to PlaybackTabScreen so the
     *  Playback screen can jump directly to that channel and time. Cleared
     *  by PlaybackTabScreen on first composition. */
    var pendingPlaybackChannelId by mutableStateOf<Int?>(null)
    var pendingPlaybackEpochSec  by mutableStateOf<Long?>(null)

    /** User-visible NVR display name — editable in the Device tab header and
     *  shared with LiveScreen so both show the same title. Defaults to "Device";
     *  updated by [setDisplayNvrName]. */
    private var _displayNvrName by mutableStateOf("Device")
    val displayNvrName: String get() = _displayNvrName

    fun setDisplayNvrName(name: String) {
        val trimmed = name.trim()
        if (trimmed.isNotBlank()) _displayNvrName = trimmed
    }

    private val channelNamesMap = mutableMapOf<Int, String>()
    fun channelDisplayName(id: Int): String = channelNamesMap[id] ?: "Device:${id + 1}"
    fun setChannelDisplayName(id: Int, name: String) {
        val trimmed = name.trim()
        if (trimmed.isNotBlank()) channelNamesMap[id] = trimmed
    }

    /** Offline = we have a status reading for [channelId] and it is online by
     *  NEITHER signal (NVR recorder nor publisher streamability). Returns false
     *  when we haven't read any status yet, so a freshly-loaded screen never
     *  false-flags a camera as offline. */
    fun isChannelOffline(channelId: Int): Boolean {
        val cc = connectedChannels ?: return false
        if (channelId in cc) return false
        return channelStatus.containsKey(channelId)
    }

    @Volatile private var statusRefreshing = false
    @Volatile private var lastConnectedMs = 0L

    fun loadConnectedChannels() {
        val a = api ?: return
        if (statusRefreshing) return
        val now = System.currentTimeMillis()
        if (now - lastConnectedMs < 8_000L && lastConnectedMs != 0L) return
        lastConnectedMs = now
        statusRefreshing = true
        viewModelScope.launch {
            try {
                val statusMap = mutableMapOf<Int, String>()
                val onlineSet = mutableSetOf<Int>()
                // 1) NVR recorder view (/netsdk/Stat). Accurate for N1 cameras;
                //    reports ONVIF cams (e.g. CP Plus) as "Connect Failed" even
                //    when they stream fine, because the NVR's recorder can't
                //    ingest them (K2). So this alone under-reports online.
                runCatching {
                    val ipc = a.stat().optJSONArray("IPC")
                    if (ipc != null) for (i in 0 until ipc.length()) {
                        val o = ipc.optJSONObject(i) ?: continue
                        val id = o.optInt("ID", i)
                        val status = o.optString("Status")
                        statusMap[id] = status
                        if (status.equals("Connect success", ignoreCase = true)) onlineSet.add(id)
                    }
                    channelStatus = statusMap
                    connectedChannels = onlineSet.toSet()
                }.onFailure { android.util.Log.w("NvrViewModel", "stat failed: ${it.message}") }
                // 2) Publisher view (:8080 /api/channels) — the camera is really
                //    streamable when the publisher resolved a URL + codec with no
                //    error. That's exactly what the app plays, so it's the true
                //    "online" for ONVIF cams the NVR recorder rejects.
                runCatching {
                    publisher()?.channels("sub")?.forEach { ch ->
                        // N1/template cameras never populate codec — URL present + no error
                        // is sufficient to treat a channel as streamable/online.
                        if (ch.enabled && ch.url.isNotBlank() && ch.error.isBlank())
                            onlineSet.add(ch.channel)
                    }
                    connectedChannels = onlineSet.toSet()
                }.onFailure { android.util.Log.w("NvrViewModel", "pub status failed: ${it.message}") }
                android.util.Log.i("NvrViewModel", "online=$connectedChannels status=$channelStatus")
            } finally {
                statusRefreshing = false
            }
        }
    }

    private var searchJob: Job? = null

    /** Search one channel's recordings for the UTC day containing [dayUtcMillis]. */
    fun searchRecordings(channelId: Int, dayUtcMillis: Long) {
        val a = api ?: run { recordSearchStatus = "Not connected"; return }
        searchJob?.cancel()            // cancel any in-flight search so its result can't overwrite ours
        recordSearchBusy = true
        recordSearchStatus = null
        recordSegments = null
        searchJob = viewModelScope.launch {
            try {
                val date = java.time.Instant.ofEpochMilli(dayUtcMillis)
                    .atZone(java.time.ZoneOffset.UTC).toLocalDate().toString()
                val channelMask = JSONArray()
                for (i in 0 until maxChannels) channelMask.put(if (i == channelId) "True" else "False")
                // EXACT format verified live vs firmware 3.6.6.20TestF (2026-06-06):
                // Type = 4 booleans (Timing/Motion/Alarm/Manual); CurrentPage MUST be a
                // STRING; Reload:"True" is required. Any deviation (5 types, int page,
                // missing Reload) → {"RetCode":"-1","RetDetail":"Search Failed!"}.
                // Response: {"Item":[{Channel,Type,TimeStart,TimeEnd(epoch sec),TotalSize,Disk,ID}]}.
                val typeMask = JSONArray().apply { repeat(4) { put("True") } }
                val param = JSONObject()
                    .put("Channel", channelMask)
                    .put("Type", typeMask)
                    .put("Date", date)
                    .put("BeginTime", "00:00:00")
                    .put("EndTime", "23:59:59")
                    .put("PageSize", 200)
                    .put("CurrentPage", "1")
                    .put("Reload", "True")
                val resp = JSONObject(a.searchRecord(netsdkEnvelope("R.SearchRecord", param)))
                val arr = findRecordArray(resp)
                val out = ArrayList<RecordSegment>()
                if (arr != null) for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val s = o.optLong("TimeStart", o.optLong("BeginTime", 0))
                    val e = o.optLong("TimeEnd", o.optLong("EndTime", 0))
                    if (s > 0 && e > s) out.add(
                        RecordSegment(o.optInt("Channel", channelId), s, e, o.optString("Type")))
                }
                recordSegments = out
                recordSearchStatus = if (out.isEmpty()) "No recordings for that day" else null
                android.util.Log.i("NvrViewModel", "searchRecordings ch$channelId $date -> ${out.size} segments")
            } catch (_: kotlinx.coroutines.CancellationException) {
                // superseded by a newer search — leave status/segments as-is
            } catch (t: Throwable) {
                // If cancelled while the blocking HTTP call was in-flight, skip the stale status update
                if (!isActive) return@launch
                recordSearchStatus = "Search failed: ${t.message}"
                android.util.Log.w("NvrViewModel", "searchRecordings failed: ${t.message}")
            } finally {
                recordSearchBusy = false
            }
        }
    }

    private fun findRecordArray(root: JSONObject): JSONArray? {
        listOf("fileList", "Item", "List", "RecordList", "Record", "Result", "Data").forEach { k ->
            root.optJSONArray(k)?.let { return it }
        }
        root.optJSONObject("Parameter")?.optJSONArray("Item")?.let { return it }
        root.keys().forEach { k -> root.optJSONArray(k)?.let { return it } }
        return null
    }

    // ---- Motion Events (motion-only recording search) -----------------------
    var motionEventSegments by mutableStateOf<List<RecordSegment>?>(null)
    var motionEventBusy     by mutableStateOf(false)
    var motionEventStatus   by mutableStateOf<String?>(null)
    private var motionEventJob: Job? = null

    fun searchMotionEvents(channelId: Int, dayUtcMillis: Long) {
        val a = api ?: run { motionEventStatus = "Not connected"; return }
        motionEventJob?.cancel()
        motionEventBusy = true
        motionEventStatus = null
        motionEventSegments = null
        motionEventJob = viewModelScope.launch {
            try {
                val date = java.time.Instant.ofEpochMilli(dayUtcMillis)
                    .atZone(java.time.ZoneOffset.UTC).toLocalDate().toString()
                val channelMask = JSONArray()
                for (i in 0 until maxChannels) channelMask.put(if (i == channelId) "True" else "False")
                // Type = [Timing, Motion, Alarm, Manual] — Motion only (index 1)
                val typeMask = JSONArray().apply {
                    put("False"); put("True"); put("False"); put("False")
                }
                val param = JSONObject()
                    .put("Channel", channelMask)
                    .put("Type", typeMask)
                    .put("Date", date)
                    .put("BeginTime", "00:00:00")
                    .put("EndTime", "23:59:59")
                    .put("PageSize", 200)
                    .put("CurrentPage", "1")
                    .put("Reload", "True")
                val resp = JSONObject(a.searchRecord(netsdkEnvelope("R.SearchRecord", param)))
                val arr = findRecordArray(resp)
                val out = ArrayList<RecordSegment>()
                if (arr != null) for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val s = o.optLong("TimeStart", o.optLong("BeginTime", 0))
                    val e = o.optLong("TimeEnd", o.optLong("EndTime", 0))
                    if (s > 0 && e > s) out.add(
                        RecordSegment(o.optInt("Channel", channelId), s, e, o.optString("Type")))
                }
                motionEventSegments = out
                motionEventStatus = if (out.isEmpty()) "No motion events for that day" else null
            } catch (_: kotlinx.coroutines.CancellationException) {
            } catch (t: Throwable) {
                if (!isActive) return@launch
                motionEventStatus = "Search failed: ${t.message}"
            } finally {
                motionEventBusy = false
            }
        }
    }

    fun isMotionEnabled(channelId: Int): Boolean {
        val arr = motionDetectionCfg?.optJSONArray("MotionDetection") ?: return false
        for (i in 0 until arr.length()) {
            val ch = arr.optJSONObject(i) ?: continue
            if (ch.optInt("ID") == channelId)
                return ch.optString("MDEnable").equals("True", ignoreCase = true)
        }
        return false
    }

    @Volatile private var replayTunnel: RemoteSession? = null

    /** Resolve the host:port for the :10000 replay stream. LAN → (host, 10000);
     *  Remote → opens (or reuses) the `<deviceId>-replay` libjuice tunnel and
     *  returns ("127.0.0.1", localPort). Null if the tunnel can't be opened. */
    suspend fun replayEndpoint(): Pair<String, Int>? {
        val creds = credentials ?: return null
        if (!creds.remote) return creds.host to 10000
        val cur = replayTunnel
        if (cur != null && cur.isAlive) return "127.0.0.1" to cur.localPort
        cur?.let { runCatching { it.close() } }
        replayTunnel = null
        val sid = "${creds.deviceId}-replay"
        android.util.Log.i("NvrViewModel", "opening replay tunnel $sid")
        val ns = RemoteSession(sid, remoteConfig)
        if (!ns.connect()) {
            ns.close()
            android.util.Log.w("NvrViewModel", "replay tunnel connect failed for $sid")
            return null
        }
        replayTunnel = ns
        android.util.Log.i("NvrViewModel", "replay tunnel ready: $sid -> 127.0.0.1:${ns.localPort}")
        return "127.0.0.1" to ns.localPort
    }

    // ------------------------------------------------------------------
    // Download recording — captures a RecordSegment to the device's
    // Movies/ArcisNVR folder via the :10000 replay WebSocket protocol.
    // Muxes the raw H.265/H.264 NALUs into an MP4 file via MediaMuxer.
    // ------------------------------------------------------------------
    var downloadProgress  by mutableStateOf<Float?>(null)
    var downloadStatus    by mutableStateOf<String?>(null)
    @Volatile private var activeDownloadClient: WsReplayClient? = null

    fun downloadRecording(segment: RecordSegment) {
        if (downloadProgress != null) return
        val ctx   = getApplication<Application>()
        val creds = credentials ?: run { downloadStatus = "Not connected"; return }
        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) { downloadProgress = 0f; downloadStatus = "Opening replay…" }

            val ep = replayEndpoint() ?: run {
                withContext(Dispatchers.Main) {
                    downloadProgress = null; downloadStatus = "Cannot open replay tunnel"
                }
                return@launch
            }

            val totalSec  = (segment.endSec - segment.startSec).coerceAtLeast(1).toFloat()
            val tmpFile   = File(ctx.cacheDir, "nvr_dl_${segment.startSec}.mp4")
            var muxer: MediaMuxer? = null
            var trackIdx  = -1
            var frameCount = 0L
            val latch     = CountDownLatch(1)
            var endNormal = false

            // IOTDaemon on port 10000 validates credentials; P2P creds are empty strings
            // (cloud-auth mode), so fall back to NVR factory admin user.
            val wsUser = creds.username.ifBlank { "admin" }
            val wsPass = creds.password
            val wsClient = WsReplayClient(
                ep.first, ep.second, wsUser, wsPass,
                segment.channel, segment.startSec, segment.endSec,
                onFrame = { codec, isKey, w, h, data, _ ->
                    if (muxer == null) {
                        if (!isKey) return@WsReplayClient
                        val fmt = MediaFormat.createVideoFormat(
                            codec, w.coerceIn(16, 7680), h.coerceIn(16, 4320))
                        val m = MediaMuxer(tmpFile.absolutePath,
                            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                        trackIdx = m.addTrack(fmt)
                        m.start()
                        muxer = m
                        viewModelScope.launch(Dispatchers.Main) { downloadStatus = "Downloading…" }
                    }
                    val m = muxer ?: return@WsReplayClient
                    val ptsUs = frameCount * 33333L
                    val bb    = java.nio.ByteBuffer.wrap(data)
                    val info  = MediaCodec.BufferInfo().also {
                        it.set(0, data.size, ptsUs,
                            if (isKey) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    }
                    runCatching { m.writeSampleData(trackIdx, bb, info) }
                    frameCount++
                    if (frameCount % 30L == 0L) {
                        val prog = (ptsUs / 1_000_000f / totalSec).coerceIn(0f, 0.99f)
                        viewModelScope.launch(Dispatchers.Main) { downloadProgress = prog }
                    }
                },
                onStatus = {},
                onError  = { msg ->
                    endNormal = msg.contains("closed", ignoreCase = true)
                    latch.countDown()
                },
            )
            activeDownloadClient = wsClient
            wsClient.start()

            val timeoutMs = ((totalSec + 60f) * 1000f).toLong()
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            wsClient.stop()
            activeDownloadClient = null

            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }

            if (!endNormal || frameCount == 0L) {
                tmpFile.delete()
                withContext(Dispatchers.Main) { downloadProgress = null; downloadStatus = "Download failed" }
                return@launch
            }

            // Copy temp file → MediaStore (Movies/ArcisNVR)
            val cv = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME,
                    "NVR_Ch${segment.channel + 1}_${segment.startSec}.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ArcisNVR")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv)
            if (uri != null) {
                ctx.contentResolver.openOutputStream(uri)?.use { out ->
                    tmpFile.inputStream().use { it.copyTo(out) }
                }
            }
            tmpFile.delete()

            withContext(Dispatchers.Main) {
                downloadProgress = null
                downloadStatus = if (uri != null) "Saved to Movies/ArcisNVR" else "Save failed"
            }
        }
    }

    fun cancelDownload() {
        activeDownloadClient?.stop()
        activeDownloadClient = null
        downloadProgress = null
        downloadStatus   = null
    }

    var batchRemaining by mutableStateOf(0)
        private set
    private var batchJob: Job? = null

    fun startBatchDownload(segments: List<RecordSegment>) {
        if (segments.isEmpty()) return
        batchJob?.cancel()
        batchRemaining = segments.size
        batchJob = viewModelScope.launch {
            for (seg in segments) {
                if (!isActive) break
                while (downloadProgress != null && isActive) kotlinx.coroutines.delay(300)
                if (!isActive) break
                downloadRecording(seg)
                kotlinx.coroutines.delay(600)
                while (downloadProgress != null && isActive) kotlinx.coroutines.delay(300)
                if (!isActive) break
                batchRemaining = (batchRemaining - 1).coerceAtLeast(0)
            }
            batchRemaining = 0
        }
    }

    fun cancelBatchDownload() {
        batchJob?.cancel()
        batchJob = null
        batchRemaining = 0
        cancelDownload()
    }

    // ------------------------------------------------------------------
    // PTZ control — talks to camera HTTP directly.
    //
    //  - LAN mode:    PtzClient → camera-IP:80 (direct)
    //  - Remote mode: PtzClient → 127.0.0.1:<libjuice-tunnel-port> →
    //                 NVR's tcpsvd 8540+N → camera-IP:80
    //
    // The NVR runs a per-channel HTTP libjuice service (`<deviceId>-c<N>-http`)
    // exactly mirroring the per-channel RTSP services. Lazy-opened on first
    // PTZ press, cached for the session lifetime.
    // ------------------------------------------------------------------
    var ptzStatus by mutableStateOf<String?>(null)
    /** When non-null, the Live screen shows a popup dialog (e.g. "This camera
     *  has no PTZ."). The publisher is the source of truth for PTZ capability. */
    var ptzPopupMessage by mutableStateOf<String?>(null)
    /** Channels the publisher has reported as having no PTZ — so the PTZ button
     *  shows the popup immediately instead of opening a dead control pad. */
    var ptzUnsupportedChannels by mutableStateOf<Set<Int>>(emptySet())
        private set

    /** UI-side PTZ capability descriptor for a channel. Doesn't do any
     *  network work — pure protocol-name heuristic to grey out the pad
     *  for cams known not to expose PTZ. Publisher remains source of
     *  truth for actual dispatch. */
    fun ptzClientFor(channelId: Int): PtzClient? {
        val entry = findIpCamEntry(channelId) ?: return null
        return PtzClient.fromIpCamEntry(entry)
    }

    /** Vector for a PTZ direction, in ONVIF's normalised [-1, 1] units.
     *  Velocity magnitude scales with [speed] (1..8 maps to 0.125..1.0). */
    private fun ptzVector(dir: PtzClient.Dir, speed: Int): Triple<Double, Double, Double> {
        val s = (speed.coerceIn(1, 8)) / 8.0
        return when (dir) {
            PtzClient.Dir.UP         -> Triple( 0.0,        s,    0.0)
            PtzClient.Dir.DOWN       -> Triple( 0.0,       -s,    0.0)
            PtzClient.Dir.LEFT       -> Triple(-s,          0.0,  0.0)
            PtzClient.Dir.RIGHT      -> Triple( s,          0.0,  0.0)
            PtzClient.Dir.LEFT_UP    -> Triple(-s,          s,    0.0)
            PtzClient.Dir.LEFT_DOWN  -> Triple(-s,         -s,    0.0)
            PtzClient.Dir.RIGHT_UP   -> Triple( s,          s,    0.0)
            PtzClient.Dir.RIGHT_DOWN -> Triple( s,         -s,    0.0)
            PtzClient.Dir.ZOOM_IN    -> Triple( 0.0,        0.0,  s)
            PtzClient.Dir.ZOOM_OUT   -> Triple( 0.0,        0.0, -s)
        }
    }

    fun ptzStart(channelId: Int, dir: PtzClient.Dir, speed: Int = 4) {
        android.util.Log.i("NvrVM-Ptz", "ptzStart ch=$channelId dir=$dir speed=$speed")
        val api = publisher()
        if (api == null) { ptzStatus = "Not logged in"; return }
        val (pan, tilt, zoom) = ptzVector(dir, speed)
        viewModelScope.launch {
            val r = api.ptzMove(channelId, pan, tilt, zoom)
            android.util.Log.i("NvrVM-Ptz", "ptzStart ch=$channelId dir=$dir -> ok=${r.ok} err=${r.error}")
            if (!r.ok) {
                // Publisher returns "camera does not support PTZ (fixed-position model)"
                // for bullet/fixed-dome and "PTZ not implemented for N1 cams" for N1.
                val err = extractError(r.error) ?: "PTZ rejected"
                // A fixed camera rejects PTZ in several ways: an explicit "does not
                // support PTZ" / "not implemented" message, OR (ONVIF cams like CP
                // Plus) a SOAP fault such as "InvalidArgVal". Treat all of these as
                // "no PTZ" and show the clean popup instead of the raw fault text.
                val noPtz = listOf("support", "not implemented", "fixed", "no ptz",
                                   "fault", "invalidarg", "invalid arg", "not exist", "rejected")
                    .any { err.contains(it, ignoreCase = true) }
                if (noPtz) {
                    ptzUnsupportedChannels = ptzUnsupportedChannels + channelId
                    ptzPopupMessage = "This camera has no PTZ."
                } else {
                    ptzStatus = err
                }
            }
        }
    }

    fun ptzStop(channelId: Int) {
        android.util.Log.i("NvrVM-Ptz", "ptzStop ch=$channelId")
        val api = publisher() ?: return
        viewModelScope.launch {
            val r = api.ptzStop(channelId)
            android.util.Log.i("NvrVM-Ptz", "ptzStop ch=$channelId -> ok=${r.ok}")
        }
    }

    fun ptzGotoPreset(channelId: Int, preset: Int) {
        val a = api ?: run { ptzStatus = "Not connected"; return }
        viewModelScope.launch {
            try {
                a.preset(channelId, "goto", preset)
                ptzStatus = null
            } catch (e: Exception) {
                val msg = (e as? NetSdkException)?.responseBody?.take(80) ?: e.message?.take(80)
                ptzStatus = "Preset $preset: ${msg ?: "error"}"
            }
        }
    }

    fun ptzSetPreset(channelId: Int, preset: Int) {
        val a = api ?: run { ptzStatus = "Not connected"; return }
        viewModelScope.launch {
            try {
                a.preset(channelId, "set", preset)
                ptzStatus = "Preset $preset saved"
            } catch (e: Exception) {
                val msg = (e as? NetSdkException)?.responseBody?.take(80) ?: e.message?.take(80)
                ptzStatus = "Save failed: ${msg ?: "error"}"
            }
        }
    }

    // Auto Cruise — software: cycle presets 1-8 with a configurable dwell time
    var autoCruiseActive by mutableStateOf(false)
        private set
    private var cruiseJob: Job? = null

    fun startAutoCruise(channelId: Int, intervalSec: Int = 8) {
        cruiseJob?.cancel()
        autoCruiseActive = true
        cruiseJob = viewModelScope.launch {
            val a = api ?: run { autoCruiseActive = false; return@launch }
            var preset = 1
            while (isActive) {
                runCatching { a.preset(channelId, "goto", preset) }
                kotlinx.coroutines.delay(intervalSec * 1_000L)
                preset = if (preset >= 8) 1 else preset + 1
            }
        }
    }

    fun stopAutoCruise() {
        cruiseJob?.cancel()
        cruiseJob = null
        autoCruiseActive = false
    }

    fun ptzCalibrate(channelId: Int) {
        val a = api ?: run { ptzStatus = "Not connected"; return }
        viewModelScope.launch {
            try {
                a.preset(channelId, "goto", 0)
                ptzStatus = "Calibrated to home"
            } catch (e: Exception) {
                ptzStatus = "Calibration sent"
            }
            kotlinx.coroutines.delay(2_000)
            ptzStatus = null
        }
    }

    // ── Camera alarm ─────────────────────────────────────────────────────────
    var cameraAlarmActive by mutableStateOf(false)
        private set
    private var alarmJob: Job? = null
    private var alarmChatClient: CameraWsChatClient? = null

    /**
     * Returns the (connHost, connPort, cameraIp) triplet for /cgi-bin/Chat WebSocket.
     *
     * LAN  → (nvrIP, 8540+channelId, cameraIp) — NVR tcpsvd relay → camera:80.
     *         Direct camera-IP:80 is blocked when AP client isolation is active.
     * P2P  → (127.0.0.1, tunnelPort, cameraIp) — P2P tunnel to NVR's same 8540+N relay.
     *         Service ID: {deviceId}-h{N} (registered in provider-nvr-multi.conf).
     *         Tunnel → NVR:8541 → tcpsvd → camera:80 → /cgi-bin/Chat.
     */
    suspend fun chatEndpoint(channelId: Int): Triple<String, Int, String>? {
        val creds = credentials ?: return null
        val cameraIp = channels.firstOrNull { it.id == channelId }?.ipAddr.orEmpty()
        if (cameraIp.isBlank()) return null
        return if (!creds.remote) {
            // LAN: route via NVR's per-channel HTTP relay (tcpsvd 8540+N → camera:80).
            Triple(creds.host, 8540 + channelId, cameraIp)
        } else {
            // P2P: open tunnel to the same NVR relay port via the {deviceId}-h{N} service.
            val port = ensureChannelHttpTunnel(channelId) ?: return null
            Triple("127.0.0.1", port, cameraIp)
        }
    }

    fun triggerCameraAlarm(channelId: Int, durationSec: Int = 10) {
        alarmJob?.cancel()
        alarmChatClient?.stop(); alarmChatClient = null

        cameraAlarmActive = true
        alarmJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val user = credentials?.username.orEmpty().ifBlank { "admin" }
            val pass = credentials?.password ?: ""

            // /cgi-bin/Chat is N1/Adiance-only. For ONVIF/HIK/DAHUA cameras skip
            // Chat and go directly to the NVR alarm trigger endpoint.
            val proto = channels.firstOrNull { it.id == channelId }?.protocol.orEmpty().uppercase()
            val isN1 = proto.isBlank() || proto in setOf("N1", "HICHIP")
            val ep = if (isN1) chatEndpoint(channelId) else null

            if (ep == null || ep.first.isBlank()) {
                // No Chat endpoint available (or non-N1 camera) — use NVR HTTP alarm trigger.
                runCatching { api?.triggerSiren(channelId, durationSec) }
                kotlinx.coroutines.delay(durationSec * 1_000L)
                cameraAlarmActive = false
                return@launch
            }

            val tone = CameraWsChatClient.readWavAssetToAlaw(getApplication(), "siren.wav")
            var chatDone = false
            val chatClient = CameraWsChatClient(
                host = ep.first, port = ep.second,
                username = user, password = pass,
                cameraHost = ep.third,
                onReady = {
                    viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        var offset = 0
                        while (offset < tone.size && cameraAlarmActive) {
                            val end = minOf(offset + 160, tone.size)
                            alarmChatClient?.sendAudio(tone.copyOfRange(offset, end))
                            offset = end
                            kotlinx.coroutines.delay(20)
                        }
                        chatDone = true
                        alarmChatClient?.stop(); alarmChatClient = null
                        cameraAlarmActive = false
                    }
                },
                onError = {
                    if (!chatDone) {
                        chatDone = true
                        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            runCatching { api?.triggerSiren(channelId, durationSec) }
                            kotlinx.coroutines.delay(durationSec * 1_000L)
                            cameraAlarmActive = false
                        }
                    }
                },
            )
            alarmChatClient = chatClient
            chatClient.start()
        }
    }

    fun stopCameraAlarm(@Suppress("UNUSED_PARAMETER") channelId: Int) {
        alarmJob?.cancel()
        alarmChatClient?.stop(); alarmChatClient = null
        viewModelScope.launch {
            runCatching { api?.stopSiren() }
            runCatching { api?.stopAlarmLight() }
        }
        cameraAlarmActive = false
    }

    // ---- Per-channel audio volume (app-side, no camera API) ------------------
    // Stored in SharedPreferences as a float gain factor (0.0 – 1.0).
    // Applied to the AudioTrack in WsAudioListenClient.

    fun channelAudioGain(channelId: Int): Float =
        audioPrefs.getFloat("gain_$channelId", 1.0f)

    fun setChannelAudioGain(channelId: Int, gain: Float) {
        audioPrefs.edit().putFloat("gain_$channelId", gain.coerceIn(0f, 1f)).apply()
    }

    /** Pull the human-readable bit out of the publisher's JSON error.
     *  e.g. `{"error":"camera does not support PTZ ..."}` → "camera does..." */
    private fun extractError(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return try {
            JSONObject(raw).optString("error", raw)
        } catch (_: Throwable) { raw }
    }

    // ------------------------------------------------------------------
    // Setting > Ordinary — device info + general + time
    // ------------------------------------------------------------------
    var deviceInfo by mutableStateOf<JSONObject?>(null)
    var general by mutableStateOf<JSONObject?>(null)
    var networkCfg by mutableStateOf<JSONObject?>(null)
    var smtpCfg by mutableStateOf<JSONObject?>(null)
    var wifiCfg by mutableStateOf<JSONObject?>(null)
    var encodeCfg by mutableStateOf<JSONArray?>(null)
    var osdCfg by mutableStateOf<JSONObject?>(null)
    var localTimeRaw by mutableStateOf<String?>(null)
    var generalTimeCfg by mutableStateOf<JSONObject?>(null)
    var generalMaintCfg by mutableStateOf<JSONObject?>(null)
    var pppoeCfg by mutableStateOf<JSONObject?>(null)
    var diskStat by mutableStateOf<JSONObject?>(null)
    var usersCfg by mutableStateOf<JSONObject?>(null)
    var logsCfg by mutableStateOf<JSONObject?>(null)
    var settingStatus by mutableStateOf<String?>(null)

    fun loadOrdinary() = launchBlock({ api?.deviceInfo() }) {
        deviceInfo = it
        maxChannels = it.optString("MAX_CHN").toIntOrNull()?.takeIf { n -> n in 1..32 } ?: maxChannels
        credentials?.deviceId?.takeIf { id -> id.isNotBlank() }
            ?.let { id -> channelCountCache[id] = maxChannels }
        android.util.Log.i("NvrViewModel", "deviceInfo MAX_CHN=$maxChannels")
    }
    fun loadGeneral() = launchBlock({ api?.general() }) { general = it }
    fun loadNetworkCfg() = launchBlock({ api?.network() }) { networkCfg = it }
    fun loadSmtp() = launchBlock({ api?.smtp() }) { smtpCfg = it }
    fun loadWifi() = launchBlock({ api?.wifi() }) { wifiCfg = it }
    fun loadEncode() = launchBlock({ api?.streamEncode() }) { encodeCfg = it }
    // OSD edits the full /netsdk/Stream object (Title/OSD/Ircut/Encode/Color/Ptz)
    // and round-trips it on save so untouched fields are preserved.
    fun loadOsd() = launchBlock({ api?.streamConfig()?.let { JSONObject(it) } }) { osdCfg = it }

    fun saveGeneral(updated: JSONObject) =
        launchSave({ api?.setGeneral(updated) }, "General saved") { loadGeneral() }
    fun saveNetwork(updated: JSONObject) =
        launchSave({ api?.setNetwork(updated) }, "Network saved") { loadNetworkCfg() }
    fun saveSmtp(updated: JSONObject) =
        launchSave({ api?.setSmtp(updated) }, "SMTP saved") { loadSmtp() }
    fun saveWifi(updated: JSONObject) =
        launchSave({ api?.setWifi(updated) }, "Wi-Fi saved") { loadWifi() }
    fun saveEncode(updated: JSONArray) {
        viewModelScope.launch {
            settingStatus = null
            try {
                api?.setStreamEncode(updated)
                settingStatus = "Encoding saved"
                loadEncode()
                // Encoding changes (resolution/FPS/bitrate) cause the camera to restart its
                // RTSP stream. Give the camera 3s to restart, then reconnect VLC.
                kotlinx.coroutines.delay(3_000)
                streamRefreshToken++
            } catch (t: Throwable) {
                settingStatus = "Failed: ${t.message}"
            }
        }
    }
    fun saveOsd(updated: JSONObject) =
        launchSave({ api?.setStream(updated) }, "OSD saved") { loadOsd() }

    /** Bumped after any settings change that causes the camera to restart its
     *  RTSP stream (IR cut mode, encoding). LiveScreen observes this to reconnect VLC.
     *  Also bumped on P2P reconnect so the live screen re-fetches the tunnel URL. */
    var streamRefreshToken by mutableStateOf(0)
        private set

    /** Bumped on every successful P2P reconnect. LiveScreen observes this to re-fetch
     *  the talkback/audio-listen endpoint (replay tunnel port changes on reconnect). */
    var reconnectToken by mutableStateOf(0)
        private set

    fun saveIrcutMode(channelId: Int, mode: String) {
        val a = api ?: run { settingStatus = "Not connected"; return }
        viewModelScope.launch {
            settingStatus = null
            try {
                val arr = JSONArray().put(JSONObject().put("ID", channelId).put("IrcutModeCur", mode))
                a.setStreamIrcut(arr)
                settingStatus = null
                kotlinx.coroutines.delay(2_500)
                streamRefreshToken++
            } catch (t: Throwable) {
                settingStatus = "Save failed: ${t.message}"
            }
        }
    }

    // ---- Siren / buzzer -------------------------------------------------------
    var sirenActive by mutableStateOf(false)
        private set
    private var sirenChatClient: CameraWsChatClient? = null

    fun triggerSiren(channelId: Int = 1) {
        sirenChatClient?.stop(); sirenChatClient = null
        sirenActive = true
        val user = credentials?.username.orEmpty().ifBlank { "admin" }
        val pass = credentials?.password ?: ""

        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val ep = chatEndpoint(channelId)  // suspend: opens P2P tunnel if remote
            android.util.Log.d("Siren", "triggerSiren ch=$channelId ep=$ep")

            if (ep != null && ep.third.isNotBlank()) {
                val tone = CameraWsChatClient.readWavAssetToAlaw(getApplication(), "siren.wav")
                android.util.Log.d("Siren", "WAV ${tone.size}B → ws://${ep.first}:${ep.second}/cgi-bin/Chat cameraHost=${ep.third}")
                val client = CameraWsChatClient(
                    host = ep.first, port = ep.second,
                    username = user, password = pass,
                    cameraHost = ep.third,
                    onReady = {
                        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            var offset = 0
                            while (offset < tone.size && sirenActive) {
                                val end = minOf(offset + 160, tone.size)
                                sirenChatClient?.sendAudio(tone.copyOfRange(offset, end))
                                offset = end
                                kotlinx.coroutines.delay(20)
                            }
                            android.util.Log.d("Siren", "done offset=$offset active=$sirenActive")
                            sirenChatClient?.stop(); sirenChatClient = null
                            sirenActive = false
                        }
                    },
                    onError = { msg ->
                        android.util.Log.e("Siren", "Chat error: $msg")
                        sirenActive = false; sirenChatClient = null
                    },
                )
                sirenChatClient = client
                client.start()
            } else {
                // Fallback: NVR HTTP alarm trigger (P2P tunnel failed or channel not found)
                android.util.Log.d("Siren", "No chat endpoint — NVR HTTP trigger ch=$channelId")
                val a = api ?: run { settingStatus = "Not connected"; sirenActive = false; return@launch }
                try {
                    a.triggerSiren(channelId, durationSec = 10)
                    settingStatus = "Siren triggered"
                    kotlinx.coroutines.delay(10_000)
                    sirenActive = false
                } catch (t: Throwable) {
                    settingStatus = "Siren failed: ${t.message}"
                    sirenActive = false
                }
            }
        }
    }

    fun stopSiren() {
        // Stop direct camera chat client if active
        sirenChatClient?.stop()
        sirenChatClient = null

        val a = api ?: run { sirenActive = false; return }
        viewModelScope.launch {
            runCatching { a.stopSiren() }
            sirenActive = false
            settingStatus = "Siren stopped"
        }
    }

    // ---- Motion Detection -------------------------------------------------------
    var motionDetectionCfg by mutableStateOf<JSONObject?>(null)

    fun loadMotionDetection() = launchBlock({ api?.event() }) { motionDetectionCfg = it }

    fun saveMotionDetection(channelId: Int, mdEnable: Boolean, humanEnable: Boolean, appAlarm: Boolean) {
        val a = api ?: run { settingStatus = "Not connected"; return }
        viewModelScope.launch {
            try {
                val full = a.event()
                val mdArr = full.optJSONArray("MotionDetection") ?: JSONArray()
                for (i in 0 until mdArr.length()) {
                    val ch = mdArr.getJSONObject(i)
                    if (ch.optInt("ID") == channelId) {
                        ch.put("MDEnable", if (mdEnable) "True" else "False")
                        val humanArr = ch.optJSONArray("humanDetect") ?: JSONArray()
                        for (j in 0 until humanArr.length()) {
                            humanArr.getJSONObject(j).put("HumanEnable", if (humanEnable) "True" else "False")
                        }
                        val actions = ch.optJSONObject("Actions")
                            ?: JSONObject().also { ch.put("Actions", it) }
                        actions.put("AppAlarm", if (appAlarm) "True" else "False")
                        break
                    }
                }
                full.put("MotionDetection", mdArr)
                a.setEvent(full)
                settingStatus = "Motion detection saved"
                motionDetectionCfg = a.event()
            } catch (t: Throwable) {
                settingStatus = "Save failed: ${t.message}"
            }
        }
    }

    fun rebootNvr() =
        launchSave({ api?.reboot() }, "Reboot sent") {}
    fun testSmtp() =
        launchSave({ api?.smtpTest() }, "Test mail sent") {}

    // ---- Time / Date -------------------------------------------------------
    fun loadGeneralTime() = launchBlock({ api?.generalTime() }) { generalTimeCfg = it }
    fun saveGeneralTime(updated: JSONObject) =
        launchSave({ api?.setGeneralTime(updated) }, "Time saved") { loadGeneralTime() }

    fun syncTimeWithPhone(onDone: (Boolean, String) -> Unit) {
        val a = api ?: run { onDone(false, "Not connected"); return }
        viewModelScope.launch {
            try {
                val now = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC)
                val fmt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                a.setSystemTime(org.json.JSONObject().put("DateTime", now.format(fmt)))
                withContext(kotlinx.coroutines.Dispatchers.Main) { onDone(true, "Device time synced") }
            } catch (t: Throwable) {
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    onDone(false, "Sync failed: ${t.message}")
                }
            }
        }
    }

    // ---- Scheduled Maintenance --------------------------------------------
    fun loadGeneralMaint() = launchBlock({ api?.generalMaintenance() }) { generalMaintCfg = it }
    fun saveGeneralMaint(updated: JSONObject) =
        launchSave({ api?.setGeneralMaintenance(updated) }, "Maintenance schedule saved") { loadGeneralMaint() }

    // ---- PPPoE (DSL dial-up) ----------------------------------------------
    // Same /netsdk/Network/* family as Wi-Fi/SMTP — bare-object round-trip,
    // no {DEV,VER,API,Parameter} envelope (that envelope is only for the
    // "API action" endpoints like LogSearch / AddUser / R.SearchRecord).
    fun loadPppoe() = launchBlock({ api?.pppoe() }) { pppoeCfg = it }
    fun savePppoe(updated: JSONObject) =
        launchSave({ api?.setPppoe(updated) }, "PPPoE saved") { loadPppoe() }

    // ---- Storage / Disk (read-only) ---------------------------------------
    fun loadDiskStat() = launchBlock({ api?.stat() }) { diskStat = it }

    // ---- Users (read-only list) -------------------------------------------
    // GET /netsdk/User needs no body. Add/edit/delete are deferred: they
    // mutate live NVR auth (risk of locking out admin mid-demo) and the
    // AddUser Parameter schema isn't verified against this firmware yet.
    fun loadUsers() = launchBlock({ api?.users()?.let { JSONObject(it) } }) { usersCfg = it }

    // ---- Logs (read-only search) ------------------------------------------
    // LogSearch is non-destructive. Body must be the firmware envelope
    // {DEV,VER,API:"LogSearch",Parameter:{...}} (verified against the NVR web
    // UI bundle). Type 0 = all log types; broad page so the demo shows data.
    fun loadLogs() {
        val a = api ?: run { settingStatus = "Not connected"; return }
        settingStatus = "Loading logs…"
        viewModelScope.launch {
            try {
                val param = JSONObject()
                    .put("Type", 0)
                    .put("PageSize", 50)
                    .put("CurrentPage", 1)
                logsCfg = JSONObject(a.logSearch(netsdkEnvelope("LogSearch", param)))
                settingStatus = null
            } catch (t: Throwable) {
                settingStatus = "Log search failed: ${t.message}"
            }
        }
    }

    /** Wrap a Parameter object in the firmware's action envelope. Used by the
     *  "API action" endpoints (LogSearch, R.SearchRecord, AddUser, …) which —
     *  unlike the bare Network-config PUTs — expect this outer shape. */
    private fun netsdkEnvelope(apiName: String, parameter: JSONObject): String =
        JSONObject()
            .put("DEV", "XVR").put("VER", "1.0")
            .put("API", apiName).put("Parameter", parameter)
            .toString()

    // ---- Change password ---------------------------------------------------
    /** Posts to /netsdk/SetPasswd. NVR firmware expects User/OldPasswd/NewPasswd. */
    fun changePassword(user: String, oldPwd: String, newPwd: String, onDone: (Boolean) -> Unit) {
        val a = api ?: run { settingStatus = "Not connected"; onDone(false); return }
        settingStatus = "Updating password…"
        viewModelScope.launch {
            try {
                val body = JSONObject().apply {
                    put("User", user)
                    put("OldPasswd", oldPwd)
                    put("NewPasswd", newPwd)
                }
                a.setPasswd(body)
                settingStatus = "Password updated"
                onDone(true)
            } catch (t: Throwable) {
                settingStatus = "Update failed: ${t.message}"
                onDone(false)
            }
        }
    }

    // ---- Image / Color -----------------------------------------------------
    // Per-channel image settings now route through the publisher's
    // /api/channels/{n}/image endpoint, which translates to ONVIF SOAP against
    // the actual camera (not the NVR's local table). Verified 2026-06-02 end-to-end
    // against CP Plus (ONVIF :80) + Adiance AD-90 (ONVIF :8888).
    //
    // We keep one JSONObject per channel (channelId → settings).
    var perChannelColor by mutableStateOf<Map<Int, JSONObject>>(emptyMap())
    var perChannelColorFailed by mutableStateOf<Set<Int>>(emptySet())

    fun loadColorFor(channelId: Int) {
        viewModelScope.launch {
            perChannelColorFailed = perChannelColorFailed - channelId
            try {
                val pub = publisher() ?: run {
                    settingStatus = "Publisher unreachable"
                    perChannelColorFailed = perChannelColorFailed + channelId
                    return@launch
                }
                val obj = pub.imageGet(channelId)
                perChannelColor = perChannelColor.toMutableMap().apply { put(channelId, obj) }
            } catch (t: NetSdkException) {
                settingStatus = "Camera ${channelId + 1} image read failed: HTTP ${t.httpCode}"
                perChannelColorFailed = perChannelColorFailed + channelId
            } catch (t: Throwable) {
                settingStatus = "Camera ${channelId + 1} image read failed: ${t.message}"
                perChannelColorFailed = perChannelColorFailed + channelId
            }
        }
    }

    fun saveColorFor(channelId: Int, settings: JSONObject) {
        viewModelScope.launch {
            try {
                val pub = publisher() ?: run {
                    settingStatus = "Publisher unreachable"
                    return@launch
                }
                val echoed = pub.imageSet(channelId, settings)
                perChannelColor = perChannelColor.toMutableMap().apply { put(channelId, echoed) }
                settingStatus = "Camera ${channelId + 1} image saved"
            } catch (t: NetSdkException) {
                settingStatus = "Camera ${channelId + 1}: Save failed (HTTP ${t.httpCode})"
            } catch (t: Throwable) {
                settingStatus = "Camera ${channelId + 1}: ${sanitizeError(t.message)}"
            }
        }
    }

    var settingsLoading by mutableStateOf(false)

    private fun sanitizeError(msg: String?): String {
        if (msg == null) return "Unexpected error"
        return when {
            msg.contains("127.0.0.1") || msg.contains("unexpected end", ignoreCase = true) ||
            msg.contains("ECONNREFUSED") || msg.contains("Connection refused", ignoreCase = true) ||
            msg.contains("failed to connect", ignoreCase = true) ->
                "Connection error — check NVR network"
            msg.contains("timeout", ignoreCase = true) ||
            msg.contains("timed out", ignoreCase = true) ||
            msg.contains("SocketTimeoutException", ignoreCase = true) ->
                "Request timed out — NVR may be busy"
            msg.contains("HTTP 401") || msg.contains("Unauthorized", ignoreCase = true) ->
                "Authentication failed — check NVR credentials"
            msg.length > 80 -> msg.take(80) + "…"
            else -> msg
        }
    }

    private fun <T> launchBlock(load: suspend () -> T?, onValue: (T) -> Unit) {
        viewModelScope.launch {
            settingsLoading = true
            try {
                load()?.let(onValue)
            } catch (t: Throwable) {
                settingStatus = sanitizeError(t.message)
            } finally {
                settingsLoading = false
            }
        }
    }

    private fun launchSave(call: suspend () -> String?, okMessage: String, then: () -> Unit) {
        viewModelScope.launch {
            settingStatus = null  // ensure LaunchedEffect fires even on repeated identical saves
            settingsLoading = true
            try {
                call()
                settingStatus = okMessage
                then()
            } catch (t: Throwable) {
                settingStatus = "Failed: ${sanitizeError(t.message)}"
            } finally {
                settingsLoading = false
            }
        }
    }
}
