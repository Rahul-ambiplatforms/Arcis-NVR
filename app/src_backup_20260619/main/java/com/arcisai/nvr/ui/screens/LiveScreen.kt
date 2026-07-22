package com.arcisai.nvr.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import com.arcisai.nvr.net.PtzClient
import com.arcisai.nvr.ui.theme.ArcisGray
import com.arcisai.nvr.ui.theme.ArcisGreen
import com.arcisai.nvr.viewmodel.ChannelInfo
import com.arcisai.nvr.viewmodel.NvrViewModel
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.arcisai.nvr.data.NvrCredentials
import com.arcisai.nvr.net.WsAudioListenClient
import com.arcisai.nvr.net.WsTalkbackClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import androidx.compose.runtime.snapshotFlow
import kotlin.coroutines.resume
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import android.app.Activity
import android.content.ContentValues
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import java.io.File

private enum class ViewMode { GRID, FULL }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveScreen(
    viewModel: NvrViewModel,
    channelId: Int,
    onBack: (() -> Unit)? = null,
    onNavigateToPlayback: () -> Unit = {},
    onNavigateToSettings: (channelId: Int) -> Unit = {},
    onOpenNightVision: (channelId: Int) -> Unit = {},
    onOpenPhotos: () -> Unit = {},
) {
    val isTabRoot = onBack == null

    LaunchedEffect(Unit) { viewModel.loadIpCamInfo() }

    // Pre-warm LibVLC on IO thread the moment the screen is entered.
    // VlcSingleton.get() takes 200-500ms on the main thread the first time;
    // doing it here in background means tiles find it already ready.
    val prewarmCtx = LocalContext.current
    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            VlcSingleton.get(prewarmCtx)
        }
        viewModel.loadConnectedChannels()
    }

    // Tab-root mode: refresh channels on every resume (lifecycle observer)
    if (isTabRoot) {
        val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
        DisposableEffect(lifecycle) {
            val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                    viewModel.refreshChannels()
                    viewModel.loadIpCamInfo()
                    viewModel.loadConnectedChannels()
                }
            }
            lifecycle.addObserver(obs)
            onDispose { lifecycle.removeObserver(obs) }
        }
    }

    var selectedChannel by remember { mutableIntStateOf(channelId) }
    var viewMode by remember { mutableStateOf(ViewMode.GRID) }
    var useSub by remember { mutableStateOf(true) }
    var forceTcp by remember { mutableStateOf(true) }
    var audioMuted by remember { mutableStateOf(true) }
    var showMoreSheet by remember { mutableStateOf(false) }
    var showPresets by remember { mutableStateOf(false) }
    var ptzSpeed by remember { mutableIntStateOf(4) }
    var singleChannelView by remember { mutableStateOf(false) }
    var showEventsScreen  by remember { mutableStateOf(false) }
    var snapshotTrigger by remember { mutableIntStateOf(0) }
    var isRecording by remember { mutableStateOf(false) }
    val activity = LocalContext.current as? Activity

    // Force landscape when fullscreen; restore when exiting or leaving the screen
    LaunchedEffect(viewMode) {
        activity?.requestedOrientation = if (viewMode == ViewMode.FULL)
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
    DisposableEffect(Unit) {
        onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }

    val sirenActive = viewModel.cameraAlarmActive
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    // --- Stream state (for selected channel) ---
    val ch = viewModel.channels.firstOrNull { it.id == selectedChannel }
    val boundIp = ch?.ipAddr ?: ""
    val assigned = boundIp.isNotBlank()
    val offline = viewModel.isChannelOffline(selectedChannel)
    var userForced by remember(selectedChannel, boundIp) { mutableStateOf(false) }
    var retryToken by remember(selectedChannel, useSub, boundIp) { mutableIntStateOf(0) }
    var rtsp by remember(selectedChannel, useSub, boundIp) { mutableStateOf<String?>(null) }
    var fetchExhausted by remember(selectedChannel, useSub, boundIp) { mutableStateOf(false) }
    val remoteMode = viewModel.credentials?.remote == true

    // Talkback endpoint — in LAN mode use credentials directly; in remote mode
    // open (or reuse) the replay P2P tunnel which proxies port 10000.
    var talkHost by remember { mutableStateOf(viewModel.credentials?.host ?: "") }
    var talkPort by remember { mutableIntStateOf(10000) }
    LaunchedEffect(remoteMode) {
        if (remoteMode) {
            val ep = viewModel.replayEndpoint()
            if (ep != null) { talkHost = ep.first; talkPort = ep.second }
        } else {
            talkHost = viewModel.credentials?.host ?: ""
            talkPort = 10000
        }
    }

    // Audio listen — start/stop WsAudioListenClient when user toggles the speaker.
    // Uses the same talkHost/talkPort as the talkback button (LAN or P2P tunnel).
    val audioListenClient = remember { mutableStateOf<WsAudioListenClient?>(null) }
    LaunchedEffect(audioMuted, selectedChannel, talkHost, talkPort) {
        val cur = audioListenClient.value
        if (!audioMuted && talkHost.isNotBlank()) {
            if (cur == null) {
                val wsUser = viewModel.credentials?.username.orEmpty().ifBlank { "admin" }
                val wsPass = viewModel.credentials?.password ?: ""
                val c = WsAudioListenClient(
                    host = talkHost, port = talkPort,
                    username = wsUser, password = wsPass,
                    channel = selectedChannel,
                    onReady = {},
                    onError = { /* swallow silently — audio is optional */ },
                )
                audioListenClient.value = c
                c.start()
            }
        } else {
            cur?.stop()
            audioListenClient.value = null
        }
    }
    DisposableEffect(Unit) {
        onDispose { audioListenClient.value?.stop(); audioListenClient.value = null }
    }

    val deviceName = viewModel.displayNvrName

    LaunchedEffect(selectedChannel) {
        viewModel.selectedLiveChannel = selectedChannel
        while (true) { viewModel.loadConnectedChannels(); kotlinx.coroutines.delay(20_000) }
    }

    // When a settings change causes the camera RTSP stream to restart (IR cut, encoding),
    // bump retryToken so VLC reconnects and shows the updated stream.
    LaunchedEffect(viewModel.streamRefreshToken) {
        if (viewModel.streamRefreshToken > 0) retryToken++
    }

    LaunchedEffect(selectedChannel, useSub, boundIp, retryToken) {
        fetchExhausted = false
        rtsp = null
        if (!assigned) return@LaunchedEffect
        // If offline and not force-started, wait reactively instead of restarting
        // the whole effect on every status poll (which would stop a playing stream).
        if (viewModel.isChannelOffline(selectedChannel) && !userForced) {
            snapshotFlow { !viewModel.isChannelOffline(selectedChannel) || userForced }
                .first { it }
        }
        var attempts = 0
        while (rtsp == null && attempts < 3) {
            attempts++
            rtsp = viewModel.ensureChannelStreamUrl(selectedChannel, stream = if (useSub) 1 else 0)
            if (rtsp == null && attempts < 3) kotlinx.coroutines.delay(1_500L * attempts)
        }
        if (rtsp == null) fetchExhausted = true
    }

    // --- PTZ ---
    val ptzClient = remember(selectedChannel, boundIp) { viewModel.ptzClientFor(selectedChannel) }
    val ptzAvailable = ptzClient?.isSupportedInCurrentMode == true
    LaunchedEffect(viewModel.ptzStatus) {
        if (viewModel.ptzStatus != null) { delay(3_000); viewModel.ptzStatus = null }
    }

    // Back: fullscreen → grid; grid → caller (if not tab root)
    BackHandler(enabled = viewMode != ViewMode.GRID || onBack != null) {
        when (viewMode) {
            ViewMode.FULL -> viewMode = ViewMode.GRID
            ViewMode.GRID -> onBack?.invoke()
        }
    }

    // ── FULLSCREEN — all channels, no controls, no orientation lock ───────────
    if (viewMode == ViewMode.FULL) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .systemBarsPadding(),
        ) {
            LiveChannelGrid(
                modifier = Modifier.fillMaxSize(),
                channels = viewModel.channels,
                selectedChannel = selectedChannel,
                connectedChannels = viewModel.connectedChannels,
                channelStatus = viewModel.channelStatus,
                forceTcp = forceTcp,
                remoteMode = remoteMode,
                useSub = useSub,
                audioMuted = audioMuted,
                viewModel = viewModel,
                onChannelTap = { selectedChannel = it },
                onThumbnail = { bmp -> viewModel.setChannelThumbnail(selectedChannel, bmp) },
                snapshotTrigger = snapshotTrigger,
                isRecording = isRecording,
                onRecordSaved = { name ->
                    coroutineScope.launch { snackbarHostState.showSnackbar("Saved: $name") }
                },
            )
            // Tap ✕ / back arrow to exit fullscreen
            IconButton(
                onClick = { viewMode = ViewMode.GRID },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f)),
            ) {
                Icon(Icons.Default.FullscreenExit, "Exit fullscreen", tint = Color.White, modifier = Modifier.size(20.dp))
            }
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
        return
    }

    // ── EVENTS view ───────────────────────────────────────────────────────────
    if (showEventsScreen) {
        EventsTabScreen(
            channelId = selectedChannel,
            vm = viewModel,
            onBack = { showEventsScreen = false },
            onNavigateToChannelSettings = { chId ->
                showEventsScreen = false
                onNavigateToSettings(chId)
            },
        )
        return
    }

    // ── NORMAL (GRID) view ────────────────────────────────────────────────────
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        deviceName,
                        fontWeight = FontWeight.Bold,
                        textAlign = if (isTabRoot) TextAlign.Center else TextAlign.Start,
                        modifier = if (isTabRoot) Modifier.fillMaxWidth() else Modifier,
                    )
                },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick  = { onNavigateToSettings(selectedChannel) },
                        enabled  = assigned && !offline,
                    ) {
                        Icon(Icons.Default.Settings, "Settings",
                            tint = if (assigned && !offline) LocalContentColor.current
                                   else LocalContentColor.current.copy(alpha = 0.35f))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF0D0D0D),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White,
                ),
            )
        },
        bottomBar = {
            if (!isTabRoot) {
                LiveBottomBar(
                    selectedChannel = selectedChannel,
                    creds = viewModel.credentials,
                    talkHost = talkHost,
                    talkPort = talkPort,
                    onDevice = onBack ?: {},
                    onEvents = { showEventsScreen = true },
                    showPresets = showPresets,
                    onPresetsToggle = { showPresets = !showPresets },
                    onMore = { showMoreSheet = true },
                    onTalkError = { msg ->
                        coroutineScope.launch { snackbarHostState.showSnackbar(msg) }
                    },
                )
            }
        },
        containerColor = Color.Black,
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {

            // ── 1-channel swipe pager or multi-channel grid ───────────────────
            if (singleChannelView) {
                val allChannels = viewModel.channels
                val count = allChannels.size.coerceAtLeast(1)
                // Use large virtual page count so the pager wraps: N→1 and 1→N.
                val TOTAL = count * 400
                val startPage = remember(selectedChannel, allChannels.size) {
                    100 * count + allChannels.indexOfFirst { it.id == selectedChannel }.coerceAtLeast(0)
                }
                val pagerState = rememberPagerState(
                    initialPage = startPage,
                    pageCount = { TOTAL },
                )
                var zoomScale by remember { mutableFloatStateOf(1f) }
                val zoomTransform = rememberTransformableState { zoom, _, _ ->
                    zoomScale = (zoomScale * zoom).coerceIn(1f, 5f)
                }
                // Zoom only when the current channel is actually playing
                val currentCh = allChannels.firstOrNull { it.id == selectedChannel }
                val zoomEnabled = currentCh != null &&
                    currentCh.ipAddr.isNotBlank() &&
                    viewModel.connectedChannels?.contains(selectedChannel) == true &&
                    !viewModel.isChannelOffline(selectedChannel)
                // Reset zoom immediately when the channel goes offline / loading
                LaunchedEffect(zoomEnabled) { if (!zoomEnabled) zoomScale = 1f }
                // Update selectedChannel when the user settles on a new page; reset zoom
                LaunchedEffect(pagerState.settledPage) {
                    allChannels.getOrNull(pagerState.settledPage % count)?.id?.let { selectedChannel = it }
                    zoomScale = 1f
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clipToBounds()
                        .transformable(zoomTransform, enabled = zoomEnabled),
                ) {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { scaleX = zoomScale; scaleY = zoomScale; clip = true },
                        beyondViewportPageCount = 0,
                        userScrollEnabled = zoomScale == 1f,
                    ) { rawPage ->
                        val ch = allChannels.getOrNull(rawPage % count)
                        if (ch != null) {
                            ChannelGridTile(
                                ch = ch,
                                isSelected = false,
                                connectedChannels = viewModel.connectedChannels,
                                channelStatus = viewModel.channelStatus,
                                forceTcp = forceTcp,
                                remoteMode = remoteMode,
                                useSub = useSub,
                                audioMuted = audioMuted,
                                viewModel = viewModel,
                                onTap = {},
                                onDoubleTap = { if (zoomScale > 1f) zoomScale = 1f else singleChannelView = false },
                                onThumbnail = { bmp -> viewModel.setChannelThumbnail(ch.id, bmp) },
                                snapshotTrigger = if (ch.id == selectedChannel) snapshotTrigger else 0,
                                isRecording = ch.id == selectedChannel && isRecording,
                                onRecordSaved = if (ch.id == selectedChannel) {
                                    { name -> coroutineScope.launch { snackbarHostState.showSnackbar("Saved: $name") } }
                                } else null,
                            )
                        }
                    }
                    // "2 / 4" page indicator
                    if (count > 1) {
                        Box(
                            Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 8.dp)
                                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
                                .padding(horizontal = 14.dp, vertical = 4.dp),
                        ) {
                            Text(
                                "${pagerState.currentPage % count + 1} / $count",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            } else {
                // 2×2 paged grid: always show 4 channels per page, swipe for more pages
                val allChannels = viewModel.channels
                val pageGroups = allChannels.chunked(4).let { if (it.isEmpty()) listOf(emptyList()) else it }
                val pageCount = pageGroups.size
                if (pageCount <= 1) {
                    LiveChannelGrid(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        channels = pageGroups.first(),
                        selectedChannel = selectedChannel,
                        connectedChannels = viewModel.connectedChannels,
                        channelStatus = viewModel.channelStatus,
                        forceTcp = forceTcp,
                        remoteMode = remoteMode,
                        useSub = useSub,
                        audioMuted = audioMuted,
                        viewModel = viewModel,
                        onChannelTap = { selectedChannel = it },
                        onDoubleTap = { ch -> selectedChannel = ch; singleChannelView = true },
                        onThumbnail = { bmp -> viewModel.setChannelThumbnail(selectedChannel, bmp) },
                        snapshotTrigger = snapshotTrigger,
                        isRecording = isRecording,
                        onRecordSaved = { name ->
                            coroutineScope.launch { snackbarHostState.showSnackbar("Saved: $name") }
                        },
                    )
                } else {
                    // Multiple pages — horizontal swipe between 2×2 pages
                    val gridPagerState = rememberPagerState(pageCount = { pageCount })
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        HorizontalPager(
                            state = gridPagerState,
                            modifier = Modifier.fillMaxSize(),
                            beyondViewportPageCount = 0,
                        ) { page ->
                            LiveChannelGrid(
                                modifier = Modifier.fillMaxSize(),
                                channels = pageGroups.getOrElse(page) { emptyList() },
                                selectedChannel = selectedChannel,
                                connectedChannels = viewModel.connectedChannels,
                                channelStatus = viewModel.channelStatus,
                                forceTcp = forceTcp,
                                remoteMode = remoteMode,
                                useSub = useSub,
                                audioMuted = audioMuted,
                                viewModel = viewModel,
                                onChannelTap = { selectedChannel = it },
                                onDoubleTap = { ch -> selectedChannel = ch; singleChannelView = true },
                                onThumbnail = { bmp -> viewModel.setChannelThumbnail(selectedChannel, bmp) },
                                snapshotTrigger = snapshotTrigger,
                                isRecording = isRecording,
                                onRecordSaved = { name ->
                                    coroutineScope.launch { snackbarHostState.showSnackbar("Saved: $name") }
                                },
                            )
                        }
                        // Page indicator (e.g. "2 / 4")
                        Box(
                            Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 8.dp)
                                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
                                .padding(horizontal = 14.dp, vertical = 4.dp),
                        ) {
                            Text(
                                "${gridPagerState.currentPage + 1} / $pageCount",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }

            // ── Controls bar ─────────────────────────────────────────────────
            VideoControlsBar(
                useSub = useSub,
                audioMuted = audioMuted,
                singleChannelView = singleChannelView,
                isRecording = isRecording,
                channelCount = viewModel.channels.size.coerceAtLeast(1),
                onQualityToggle = { useSub = !useSub },
                onSingleChannel = { singleChannelView = true },
                onMultiChannel  = { singleChannelView = false },
                onAudioToggle   = { audioMuted = !audioMuted },
                onSnapshot      = { snapshotTrigger++ },
                onRecord        = { isRecording = !isRecording },
                onFullscreen    = { viewMode = ViewMode.FULL },
            )

            HorizontalDivider(color = Color.White.copy(alpha = 0.08f), thickness = 0.5.dp)

            // ── PTZ section ───────────────────────────────────────────────────
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LivePtzSection(
                    ptzAvailable = ptzAvailable,
                    channelOffline = offline,
                    speed = ptzSpeed,
                    onStart = { dir, speed -> viewModel.ptzStart(selectedChannel, dir, speed) },
                    onStop = { viewModel.ptzStop(selectedChannel) },
                    onViewPlayback = onNavigateToPlayback,
                )
            }

            // ── Tab-root bottom actions (Talk / Presets / More) ──────────────
            if (isTabRoot) {
                HorizontalDivider(color = Color.White.copy(alpha = 0.08f), thickness = 0.5.dp)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0D0D0D))
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LiveNavItem(Icons.Default.Alarm, "Events", { showEventsScreen = true })
                    MicHoldButton(
                        host = talkHost,
                        port = talkPort,
                        username = viewModel.credentials?.username.orEmpty().ifBlank { "admin" },
                        password = viewModel.credentials?.password ?: "",
                        channel = selectedChannel,
                        onError = { msg ->
                            coroutineScope.launch { snackbarHostState.showSnackbar(msg) }
                        },
                    )
                    LiveNavItem(Icons.Default.Star, "Presets", { showPresets = !showPresets }, selected = showPresets)
                    LiveNavItem(Icons.Default.MoreHoriz, "More", { showMoreSheet = true })
                }
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    // ── Sheets / dialogs ─────────────────────────────────────────────────────
    if (showPresets) {
        PresetsSheet(
            speed = ptzSpeed,
            onSpeedChange = { ptzSpeed = it },
            onGoto = { p -> viewModel.ptzGotoPreset(selectedChannel, p) },
            ptzAvailable = ptzAvailable,
            onDismiss = { showPresets = false },
        )
    }
    if (showMoreSheet) {
        MoreFeaturesSheet(
            onDismiss       = { showMoreSheet = false },
            onSettings      = { showMoreSheet = false; onNavigateToSettings(selectedChannel) },
            onAutoCruise    = {
                if (viewModel.autoCruiseActive) viewModel.stopAutoCruise()
                else viewModel.startAutoCruise(selectedChannel)
            },
            autoCruiseActive = viewModel.autoCruiseActive,
            onPtzCalibrate  = { viewModel.ptzCalibrate(selectedChannel) },
            onNightVision   = { showMoreSheet = false; onOpenNightVision(selectedChannel) },
            onSiren         = {
                if (viewModel.cameraAlarmActive) viewModel.stopCameraAlarm(selectedChannel)
                else viewModel.triggerCameraAlarm(selectedChannel)
            },
            sirenActive     = sirenActive,
            onPhotos        = { showMoreSheet = false; onOpenPhotos() },
        )
    }
    viewModel.ptzPopupMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { viewModel.ptzPopupMessage = null },
            confirmButton = {
                TextButton(onClick = { viewModel.ptzPopupMessage = null }) { Text("OK") }
            },
            title = { Text("PTZ unavailable") },
            text  = { Text(msg) },
        )
    }
}

// ─── Channel content (player / placeholder / loading) ──────────────────────

@Composable
private fun ChannelContent(
    modifier: Modifier,
    rtsp: String?,
    fetchExhausted: Boolean,
    assigned: Boolean,
    offline: Boolean,
    userForced: Boolean,
    forceTcp: Boolean,
    remoteMode: Boolean,
    audioMuted: Boolean,
    onUserForced: () -> Unit,
    onRetry: () -> Unit,
    onThumbnail: ((Bitmap) -> Unit)? = null,
    snapshotTrigger: Int = 0,
    isRecording: Boolean = false,
    onRecordSaved: ((String) -> Unit)? = null,
) {
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        when {
            !assigned -> PlayerPlaceholder(
                title = "No camera assigned",
                subtitle = "Assign a camera on the Cameras page.",
            )
            offline && !userForced && rtsp == null -> PlayerPlaceholder(
                title = "Camera offline",
                subtitle = "This camera isn't reachable right now.",
                actionLabel = "Try anyway",
                onAction = onUserForced,
            )
            rtsp == null -> {
                if (fetchExhausted) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            if (remoteMode) "P2P Unavailable" else "Playback Error",
                            color = Color.White, fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (remoteMode)
                                "Could not open P2P tunnel.\nCheck NVR status or try again."
                            else
                                "Network error. Try again later.",
                            color = Color.White.copy(alpha = 0.75f),
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = onRetry,
                            shape = RoundedCornerShape(50),
                        ) { Text("Retry") }
                    }
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(36.dp))
                        Spacer(Modifier.height(10.dp))
                        Text(
                            if (remoteMode) "Opening P2P tunnel…" else "Connecting…",
                            color = Color.White,
                        )
                    }
                }
            }
            else -> key(rtsp, forceTcp) {
                VlcRtspPlayer(
                    rtspUrl = rtsp, forceTcp = forceTcp, isRemote = remoteMode,
                    audioMuted = audioMuted,
                    onThumbnail = onThumbnail,
                    snapshotTrigger = snapshotTrigger,
                    isRecording = isRecording,
                    onRecordSaved = onRecordSaved,
                )
            }
        }
    }
}

// ─── 4-channel selector grid ────────────────────────────────────────────────

@Composable
private fun LiveChannelGrid(
    modifier: Modifier,
    channels: List<ChannelInfo>,
    selectedChannel: Int,
    connectedChannels: Set<Int>?,
    channelStatus: Map<Int, String>,
    forceTcp: Boolean,
    remoteMode: Boolean = false,
    useSub: Boolean,
    audioMuted: Boolean,
    viewModel: NvrViewModel,
    onChannelTap: (Int) -> Unit,
    onDoubleTap: ((Int) -> Unit)? = null,
    onThumbnail: ((Bitmap) -> Unit)? = null,
    snapshotTrigger: Int = 0,
    isRecording: Boolean = false,
    onRecordSaved: ((String) -> Unit)? = null,
) {
    var overlayChannel by remember { mutableStateOf<Int?>(null) }
    var overlayScale by remember { mutableFloatStateOf(1f) }

    Box(modifier) {
        val oc = overlayChannel
        if (oc != null) {
            val ch = channels.firstOrNull { it.id == oc }
            if (ch != null) {
                val transformState = rememberTransformableState { zoom, _, _ ->
                    overlayScale = (overlayScale * zoom).coerceIn(1f, 5f)
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                        .clipToBounds()
                        .transformable(transformState),
                ) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer { scaleX = overlayScale; scaleY = overlayScale; clip = true },
                    ) {
                        ChannelGridTile(
                            ch = ch,
                            isSelected = false,
                            connectedChannels = connectedChannels,
                            channelStatus = channelStatus,
                            forceTcp = forceTcp,
                            remoteMode = remoteMode,
                            useSub = useSub,
                            audioMuted = audioMuted,
                            viewModel = viewModel,
                            onTap = {},
                            onDoubleTap = { overlayChannel = null; overlayScale = 1f },
                            onThumbnail = { bmp -> viewModel.setChannelThumbnail(oc, bmp) },
                            snapshotTrigger = if (oc == selectedChannel) snapshotTrigger else 0,
                            isRecording = oc == selectedChannel && isRecording,
                            onRecordSaved = if (oc == selectedChannel) onRecordSaved else null,
                        )
                    }
                }
                // ← Back to grid
                IconButton(
                    onClick = { overlayChannel = null; overlayScale = 1f },
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.55f)),
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to grid",
                        tint = Color.White, modifier = Modifier.size(20.dp))
                }
                // Pinch / exit hint (only when not zoomed)
                if (overlayScale == 1f) {
                    Text(
                        "Pinch to zoom · Double-tap to exit",
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 8.dp)
                            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        fontSize = 10.sp,
                        color = Color.White.copy(alpha = 0.8f),
                    )
                }
                return@Box
            }
        }

        // ── adaptive grid: 2 cols for ≤4 ch, 4 cols for 5-16 ch ────────────────
        val cols = if (channels.size <= 4) 2 else 4
        val rows = channels.chunked(cols)
        Column(
            modifier = Modifier.fillMaxSize().background(Color.Black),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            rows.forEach { row ->
                Row(
                    Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    row.forEach { ch ->
                        key(ch.id) {
                            Box(Modifier.weight(1f).fillMaxHeight()) {
                                ChannelGridTile(
                                    ch = ch,
                                    isSelected = ch.id == selectedChannel,
                                    connectedChannels = connectedChannels,
                                    channelStatus = channelStatus,
                                    forceTcp = forceTcp,
                                    remoteMode = remoteMode,
                                    useSub = useSub,
                                    audioMuted = true,
                                    viewModel = viewModel,
                                    onTap = { onChannelTap(ch.id) },
                                    onDoubleTap = {
                                        onChannelTap(ch.id)
                                        if (onDoubleTap != null) onDoubleTap(ch.id)
                                        else { overlayChannel = ch.id; overlayScale = 1f }
                                    },
                                    onThumbnail = { bmp -> viewModel.setChannelThumbnail(ch.id, bmp) },
                                    snapshotTrigger = if (ch.id == selectedChannel) snapshotTrigger else 0,
                                    isRecording = ch.id == selectedChannel && isRecording,
                                    onRecordSaved = if (ch.id == selectedChannel) onRecordSaved else null,
                                )
                            }
                        }
                    }
                    repeat(cols - row.size) {
                        Box(Modifier.weight(1f).fillMaxHeight().background(Color(0xFF111113)))
                    }
                }
            }
            if (rows.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color.White)
                }
            }
        }
    }
}

// ─── Per-channel tile: fetches its own RTSP + runs its own VLC ───────────────

@Composable
private fun ChannelGridTile(
    ch: ChannelInfo,
    isSelected: Boolean,
    connectedChannels: Set<Int>?,
    channelStatus: Map<Int, String>,
    forceTcp: Boolean,
    remoteMode: Boolean = false,
    useSub: Boolean,
    audioMuted: Boolean,
    viewModel: NvrViewModel,
    onTap: () -> Unit,
    onDoubleTap: () -> Unit,
    onThumbnail: ((Bitmap) -> Unit)? = null,
    snapshotTrigger: Int = 0,
    isRecording: Boolean = false,
    onRecordSaved: ((String) -> Unit)? = null,
) {
    val assigned = ch.ipAddr.isNotBlank()
    val online = connectedChannels?.let { ch.id in it }
    val connecting = channelStatus[ch.id].equals("Updating", ignoreCase = true)
    val knownOffline = assigned && online == false && !connecting
    val active = assigned && (online ?: ch.enabled) && !knownOffline

    var rtsp by remember(ch.id, ch.ipAddr, useSub) { mutableStateOf<String?>(null) }

    LaunchedEffect(ch.id, ch.ipAddr, useSub) {
        rtsp = null
        if (!assigned) return@LaunchedEffect
        // Wait reactively for the channel to be online (or status unknown = reachable).
        // Using snapshotFlow avoids restarting this effect on every status poll,
        // which would interrupt a playing stream every 8-20 seconds.
        snapshotFlow { !viewModel.isChannelOffline(ch.id) }
            .first { it }
        val stream = if (useSub) 1 else 0
        var attempts = 0
        while (rtsp == null && attempts < 3 && isActive) {
            attempts++
            rtsp = viewModel.ensureChannelStreamUrl(ch.id, stream = stream)
            if (rtsp == null && attempts < 3) delay(1_500L * attempts)
        }
    }

    // When the NVR definitively marks this channel offline after we already
    // started VLC, drop the URL so the "Offline" placeholder shows immediately
    // rather than waiting for the VLC watchdog to time out.
    LaunchedEffect(ch.id) {
        snapshotFlow { viewModel.isChannelOffline(ch.id) }
            .collect { isOffline -> if (isOffline) rtsp = null }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(if (isSelected) Modifier.border(2.dp, Color.White) else Modifier)
            .pointerInput(ch.id) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { onDoubleTap() },
                )
            },
    ) {
        val url = rtsp
        if (url != null) {
            key(url, forceTcp) {
                VlcRtspPlayer(
                    rtspUrl = url,
                    forceTcp = forceTcp,
                    isRemote = remoteMode,
                    audioMuted = audioMuted,
                    onThumbnail = onThumbnail,
                    snapshotTrigger = snapshotTrigger,
                    isRecording = isRecording,
                    onRecordSaved = onRecordSaved,
                    startDelayMs = ch.id.toLong() * 600L,
                )
            }
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(if (active) Color(0xFF0E0A1E) else Color(0xFF111113)),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        if (active) Icons.Default.Videocam else Icons.Default.VideocamOff,
                        null,
                        tint = Color.White.copy(alpha = 0.3f),
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        when {
                            !assigned    -> "No cam"
                            connecting   -> "Connecting…"
                            knownOffline -> "Offline"
                            else         -> "Loading…"
                        },
                        fontSize = 9.sp,
                        color = Color.White.copy(alpha = 0.35f),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        // Channel badge
        Surface(
            color = Color.Black.copy(alpha = 0.6f),
            shape = RoundedCornerShape(3.dp),
            modifier = Modifier.align(Alignment.TopStart).padding(4.dp),
        ) {
            Text(
                "CH ${ch.id + 1}",
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                fontSize = 9.sp,
                color = Color.White,
                fontWeight = FontWeight.Bold,
            )
        }

        // Online dot
        if (assigned) {
            val dotColor = when {
                online == true  -> ArcisGreen
                connecting      -> Color(0xFFE0A800)
                online == false -> Color(0xFFE53935)
                else            -> ArcisGray
            }
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(dotColor),
            )
        }
    }
}

// ─── Controls bar ───────────────────────────────────────────────────────────

@Composable
private fun VideoControlsBar(
    useSub: Boolean,
    audioMuted: Boolean,
    singleChannelView: Boolean,
    isRecording: Boolean,
    channelCount: Int,
    onQualityToggle: () -> Unit,
    onSingleChannel: () -> Unit,
    onMultiChannel: () -> Unit,
    onAudioToggle: () -> Unit,
    onSnapshot: () -> Unit,
    onRecord: () -> Unit,
    onFullscreen: () -> Unit,
) {
    var showGridMenu by remember { mutableStateOf(false) }
    val gridLabel = if (singleChannelView) "1" else channelCount.toString()
    val gridCols  = 2  // always 2×2 grid per page
    val pageCount = (channelCount + 3) / 4

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF181818))
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Quality chip (SD / HD)
        Surface(
            onClick = onQualityToggle,
            shape = RoundedCornerShape(6.dp),
            color = Color.White.copy(alpha = 0.12f),
            modifier = Modifier.padding(4.dp),
        ) {
            Text(
                if (useSub) "SD" else "HD",
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
        }

        // Grid layout picker — replaces the old "4" / "1" chips
        Box {
            Surface(
                onClick = { showGridMenu = !showGridMenu },
                shape = RoundedCornerShape(6.dp),
                color = if (showGridMenu) Color.White.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.12f),
                modifier = Modifier.padding(4.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        Icons.Default.GridView,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = Color.White,
                    )
                    Text(
                        gridLabel,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                }
            }
            DropdownMenu(
                expanded = showGridMenu,
                onDismissRequest = { showGridMenu = false },
            ) {
                // ── 2×2 paged grid option ─────────────────────────────────
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            GridLayoutThumbnail(cols = 2, rows = 2, selected = !singleChannelView)
                            Column {
                                Text("2×2 Grid", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                                Text(
                                    if (pageCount > 1) "$pageCount pages · 4 channels each"
                                    else "$channelCount channels",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    onClick = { onMultiChannel(); showGridMenu = false },
                )
                // ── Single-channel pager option ────────────────────────────
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            GridLayoutThumbnail(cols = 1, rows = 1, selected = singleChannelView)
                            Column {
                                Text("Single Channel", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                                Text(
                                    "Swipe to switch",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    onClick = { onSingleChannel(); showGridMenu = false },
                )
            }
        }

        // Audio
        ControlIcon(
            if (audioMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
            "Audio",
            onAudioToggle,
        )
        // Snapshot
        ControlIcon(Icons.Default.CameraAlt, "Snapshot", onSnapshot)
        // Record
        ControlIcon(
            Icons.Default.FiberManualRecord,
            "Record",
            onRecord,
            tint = if (isRecording) Color.Red else Color.White.copy(alpha = 0.6f),
        )
        // Fullscreen shortcut (direct tap, same as picker option)
        ControlIcon(Icons.Default.Fullscreen, "Fullscreen", onFullscreen)
    }
}

@Composable
private fun GridLayoutThumbnail(cols: Int, rows: Int, selected: Boolean) {
    val color = if (selected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.4f)
    val fillColor = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.08f)
    Box(
        modifier = Modifier
            .size(40.dp)
            .border(1.5.dp, color, RoundedCornerShape(6.dp))
            .clip(RoundedCornerShape(6.dp))
            .background(fillColor),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(3.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            repeat(rows) {
                Row(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    repeat(cols) {
                        Box(
                            modifier = Modifier.weight(1f).fillMaxHeight()
                                .background(color.copy(alpha = 0.6f), RoundedCornerShape(2.dp)),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ControlIcon(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: Color = Color.White,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(22.dp))
    }
}

// ─── PTZ section with playback link ─────────────────────────────────────────

@Composable
private fun LivePtzSection(
    ptzAvailable: Boolean,
    channelOffline: Boolean,
    speed: Int,
    onStart: (PtzClient.Dir, Int) -> Unit,
    onStop: () -> Unit,
    onViewPlayback: () -> Unit,
) {
    Box(Modifier.fillMaxSize().padding(12.dp)) {
        Column(
            Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            when {
                channelOffline -> Text(
                    "Camera offline — PTZ unavailable",
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.4f),
                    textAlign = TextAlign.Center,
                )
                !ptzAvailable -> {
                    Text(
                        "PTZ not available for this camera",
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.4f),
                        modifier = Modifier.padding(bottom = 10.dp),
                    )
                    PtzJoystick(
                        modifier = Modifier.size(160.dp),
                        enabled = false,
                        speed = speed,
                        onStart = onStart,
                        onStop = onStop,
                    )
                }
                else -> PtzJoystick(
                    modifier = Modifier.size(160.dp),
                    enabled = true,
                    speed = speed,
                    onStart = onStart,
                    onStop = onStop,
                )
            }
        }

        // View Playback — bottom-right pill (matches reference style)
        Surface(
            onClick = onViewPlayback,
            modifier = Modifier.align(Alignment.BottomEnd),
            shape = RoundedCornerShape(50),
            color = Color.White.copy(alpha = 0.10f),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.History,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    "View Playback",
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.85f),
                )
            }
        }
    }
}

@Composable
private fun PtzJoystick(
    modifier: Modifier = Modifier,
    enabled: Boolean,
    speed: Int,
    onStart: (PtzClient.Dir, Int) -> Unit,
    onStop: () -> Unit,
) {
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var activeDir by remember { mutableStateOf<PtzClient.Dir?>(null) }

    val knobColor by animateColorAsState(
        targetValue = if (activeDir != null) Color(0xFF6B45E8) else Color.White.copy(alpha = 0.22f),
        label = "knob",
    )

    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(Color.White.copy(alpha = if (enabled) 0.09f else 0.04f))
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectDragGestures(
                    onDragStart = { dragOffset = Offset.Zero },
                    onDrag = { change, delta ->
                        change.consume()
                        val raw = dragOffset + delta
                        val dist = hypot(raw.x, raw.y)
                        val maxPx = size.width / 2.6f
                        dragOffset = if (dist > maxPx) raw * (maxPx / dist) else raw

                        val threshold = maxPx * 0.28f
                        val newDir: PtzClient.Dir? = if (hypot(dragOffset.x, dragOffset.y) < threshold) null
                        else if (abs(dragOffset.x) > abs(dragOffset.y)) {
                            if (dragOffset.x > 0) PtzClient.Dir.RIGHT else PtzClient.Dir.LEFT
                        } else {
                            if (dragOffset.y > 0) PtzClient.Dir.DOWN else PtzClient.Dir.UP
                        }

                        if (newDir != activeDir) {
                            if (activeDir != null) onStop()
                            activeDir = newDir
                            if (newDir != null) onStart(newDir, speed)
                        }
                    },
                    onDragEnd = {
                        dragOffset = Offset.Zero
                        if (activeDir != null) { onStop(); activeDir = null }
                    },
                    onDragCancel = {
                        dragOffset = Offset.Zero
                        if (activeDir != null) { onStop(); activeDir = null }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        val arrowAlpha = if (enabled) 0.35f else 0.15f
        Text("▲", color = Color.White.copy(alpha = arrowAlpha), fontSize = 13.sp,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp))
        Text("▼", color = Color.White.copy(alpha = arrowAlpha), fontSize = 13.sp,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp))
        Text("◀", color = Color.White.copy(alpha = arrowAlpha), fontSize = 13.sp,
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 10.dp))
        Text("▶", color = Color.White.copy(alpha = arrowAlpha), fontSize = 13.sp,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 10.dp))

        // Draggable knob
        Box(
            modifier = Modifier
                .offset { IntOffset(dragOffset.x.roundToInt(), dragOffset.y.roundToInt()) }
                .size(52.dp)
                .clip(CircleShape)
                .background(knobColor),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.OpenWith,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

// ─── Presets bottom sheet (pops up like More sheet) ──────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PresetsSheet(
    speed: Int,
    onSpeedChange: (Int) -> Unit,
    onGoto: (Int) -> Unit,
    ptzAvailable: Boolean,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "PRESETS",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (p in 1..4) {
                    FilledTonalButton(
                        onClick = { onGoto(p); onDismiss() },
                        enabled = ptzAvailable,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp),
                    ) {
                        Text("$p", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            Text(
                "PTZ SPEED",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Slow", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Slider(
                    value = speed.toFloat(),
                    onValueChange = { onSpeedChange(it.roundToInt()) },
                    valueRange = 1f..8f,
                    steps = 6,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                Text("Fast", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

// ─── Live-specific bottom navigation ────────────────────────────────────────

@Composable
private fun LiveBottomBar(
    selectedChannel: Int,
    creds: NvrCredentials?,
    talkHost: String = creds?.host ?: "",
    talkPort: Int = 10000,
    onDevice: () -> Unit,
    onEvents: () -> Unit,
    showPresets: Boolean,
    onPresetsToggle: () -> Unit,
    onMore: () -> Unit,
    onTalkError: ((String) -> Unit)? = null,
) {
    Surface(
        tonalElevation = 3.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(60.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LiveNavItem(Icons.Default.Videocam, "Device", onDevice)
            LiveNavItem(Icons.Default.Alarm, "Events", onEvents)
            MicHoldButton(
                host = talkHost,
                port = talkPort,
                username = creds?.username.orEmpty().ifBlank { "admin" },
                password = creds?.password ?: "",
                channel = selectedChannel,
                onError = onTalkError,
            )
            LiveNavItem(Icons.Default.Star, "Presets", onPresetsToggle, selected = showPresets)
            LiveNavItem(Icons.Default.MoreHoriz, "More", onMore)
        }
    }
}

@Composable
private fun LiveNavItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    selected: Boolean = false,
) {
    val tint = if (selected) MaterialTheme.colorScheme.primary else LocalContentColor.current
    Column(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, modifier = Modifier.size(22.dp), tint = tint)
        Text(label, fontSize = 10.sp, color = tint)
    }
}

@Composable
private fun MicHoldButton(
    host: String,
    port: Int,
    username: String,
    password: String,
    channel: Int,
    onError: ((String) -> Unit)? = null,
) {
    val ctx = LocalContext.current
    var pressed by remember { mutableStateOf(false) }

    var hasPermission by remember(ctx) {
        mutableStateOf(
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED
        )
    }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

    // Hold refs stable across recompositions; mutated from OkHttp + gesture threads.
    val talkClient = remember { mutableStateOf<WsTalkbackClient?>(null) }
    val recordThread = remember { mutableStateOf<Thread?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            recordThread.value?.interrupt()
            recordThread.value = null
            talkClient.value?.stop()
            talkClient.value = null
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(if (pressed) Color(0xFF1565C0) else Color(0xFF1E88E5))
            .pointerInput(host, channel) {
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitPointerEvent()
                        if (down.changes.any { it.pressed }) {
                            pressed = true
                            if (!hasPermission) {
                                permLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            } else if (host.isNotBlank()) {
                                val client = WsTalkbackClient(
                                    host = host, port = port,
                                    username = username, password = password,
                                    channel = channel,
                                    onReady = talkback@{
                                        val sampleRate = 8000
                                        val minBuf = AudioRecord.getMinBufferSize(
                                            sampleRate,
                                            AudioFormat.CHANNEL_IN_MONO,
                                            AudioFormat.ENCODING_PCM_16BIT,
                                        )
                                        val ar = AudioRecord(
                                            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                                            sampleRate,
                                            AudioFormat.CHANNEL_IN_MONO,
                                            AudioFormat.ENCODING_PCM_16BIT,
                                            maxOf(minBuf, 640),
                                        )
                                        val pcmBuf = ShortArray(160)  // 20 ms at 8 kHz
                                        ar.startRecording()
                                        val t = Thread {
                                            try {
                                                while (!Thread.interrupted()) {
                                                    val n = ar.read(pcmBuf, 0, pcmBuf.size)
                                                    if (n > 0) {
                                                        val g711 = ByteArray(n) {
                                                            WsTalkbackClient.pcm16ToAlaw(pcmBuf[it])
                                                        }
                                                        talkClient.value?.sendAudio(g711)
                                                    }
                                                }
                                            } finally {
                                                ar.stop()
                                                ar.release()
                                            }
                                        }.apply { isDaemon = true; start() }
                                        recordThread.value = t
                                    },
                                    onError = { msg -> pressed = false; onError?.invoke(msg) },
                                )
                                talkClient.value = client
                                client.start()
                            }
                            // Hold until finger lifts
                            while (awaitPointerEvent().changes.any { it.pressed }) { }
                            pressed = false
                            recordThread.value?.interrupt()
                            recordThread.value = null
                            talkClient.value?.stop()
                            talkClient.value = null
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Default.Mic, "Talk", tint = Color.White, modifier = Modifier.size(20.dp))
    }
    Text(
        if (pressed) "Talking…" else "Talk",
        fontSize = 10.sp,
        color = if (pressed) Color(0xFF90CAF9) else LocalContentColor.current,
    )
    } // Column
}

// ─── More features sheet ─────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoreFeaturesSheet(
    onDismiss: () -> Unit,
    onSettings: () -> Unit,
    onAutoCruise: () -> Unit = {},
    autoCruiseActive: Boolean = false,
    onPtzCalibrate: () -> Unit = {},
    onNightVision: () -> Unit = {},
    onSiren: () -> Unit = {},
    sirenActive: Boolean = false,
    onPhotos: () -> Unit = {},
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 12.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("More Features", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            IconButton(onClick = onSettings) {
                Icon(Icons.Default.Settings, "Settings")
            }
        }

        data class Feature(
            val icon: ImageVector,
            val label: String,
            val active: Boolean = false,
            val onClick: () -> Unit,
        )
        val features = listOf(
            Feature(Icons.Default.Autorenew,           "Auto Cruise",     active = autoCruiseActive, onClick = onAutoCruise),
            Feature(Icons.Default.Adjust,              "PTZ Calibration", onClick = onPtzCalibrate),
            Feature(Icons.Default.NightsStay,          "Night Vision",    onClick = onNightVision),
            Feature(Icons.Default.NotificationsActive, "Siren",           active = sirenActive,      onClick = onSiren),
            Feature(Icons.Default.Image,               "Photos",          onClick = onPhotos),
            Feature(Icons.Default.Share,               "Device Sharing",  onClick = {}),
        )

        features.chunked(4).forEach { rowItems ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.Start,
            ) {
                rowItems.forEach { feat ->
                    FeatureItem(feat.icon, feat.label, feat.active, Modifier.weight(1f), feat.onClick)
                }
                repeat(4 - rowItems.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun FeatureItem(
    icon: ImageVector,
    label: String,
    active: Boolean = false,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val activeColor = MaterialTheme.colorScheme.primary
    val bgColor = if (active) activeColor.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant
    val iconTint = if (active) activeColor else LocalContentColor.current
    Column(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = CircleShape,
            color = bgColor,
            modifier = Modifier.size(52.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, null, modifier = Modifier.size(24.dp), tint = iconTint)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            label,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            maxLines = 2,
            color = if (active) activeColor else LocalContentColor.current,
        )
    }
}

// ─── PlayerPlaceholder ───────────────────────────────────────────────────────

@Composable
private fun PlayerPlaceholder(
    title: String,
    subtitle: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            Icons.Default.VideocamOff,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.85f),
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.height(10.dp))
        Text(title, color = Color.White, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(subtitle, color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(12.dp))
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}


// ─── VlcRtspPlayer ───────────────────────────────────────────────────────────

@Composable
private fun VlcRtspPlayer(
    rtspUrl: String,
    forceTcp: Boolean,
    isRemote: Boolean = false,
    audioMuted: Boolean = false,
    onThumbnail: ((Bitmap) -> Unit)? = null,
    snapshotTrigger: Int = 0,
    isRecording: Boolean = false,
    onRecordSaved: ((String) -> Unit)? = null,
    startDelayMs: Long = 0,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var retryEpoch  by remember(rtspUrl, forceTcp, isRemote) { mutableIntStateOf(0) }
    // autoRetries persists across retryEpoch increments so we know how many
    // silent retries we've already done for this URL/mode combination.
    var autoRetries by remember(rtspUrl, forceTcp, isRemote) { mutableIntStateOf(0) }
    var state   by remember(rtspUrl, forceTcp, isRemote, retryEpoch) { mutableStateOf("Connecting…") }
    var error   by remember(rtspUrl, forceTcp, isRemote, retryEpoch) { mutableStateOf<String?>(null) }
    var playing by remember(rtspUrl, forceTcp, isRemote, retryEpoch) { mutableStateOf(false) }

    // Shared singleton — LibVLC native init is ~200-500 ms and must never be called
    // multiple times simultaneously on the main thread (causes ANR in a 4-channel grid).
    val libVlc = remember { VlcSingleton.get(context) }
    val player = remember { MediaPlayer(libVlc) }
    val videoLayout = remember { VLCVideoLayout(context) }
    // Holds the file prefix used when recording started, so stop can locate the file
    val recState = remember { object { var prefix: String? = null } }

    // ── UI overlay states ────────────────────────────────────────────────
    val flashAlpha        = remember { Animatable(0f) }
    var photoSaved        by remember { mutableStateOf(false) }
    var recordSaved       by remember { mutableStateOf(false) }
    var recordSavedLabel  by remember { mutableStateOf("") }
    var recordingElapsed  by remember { mutableIntStateOf(0) }

    // Recording elapsed-seconds counter
    LaunchedEffect(isRecording) {
        if (!isRecording) { recordingElapsed = 0; return@LaunchedEffect }
        while (isActive) { delay(1_000); recordingElapsed++ }
    }

    // Apply mute state whenever it changes
    LaunchedEffect(audioMuted) {
        player.volume = if (audioMuted) 0 else 100
    }

    // Snapshot: capture current frame and save to gallery
    LaunchedEffect(snapshotTrigger) {
        if (snapshotTrigger == 0) return@LaunchedEffect
        val sv = findSurfaceView(videoLayout) ?: return@LaunchedEffect
        if (sv.width <= 0 || sv.height <= 0) return@LaunchedEffect
        val bmp = suspendCancellableCoroutine<Bitmap?> { cont ->
            val b = Bitmap.createBitmap(sv.width, sv.height, Bitmap.Config.ARGB_8888)
            PixelCopy.request(sv, b, { result ->
                if (cont.isActive) cont.resume(if (result == PixelCopy.SUCCESS) b else null)
            }, Handler(Looper.getMainLooper()))
        } ?: return@LaunchedEffect
        val ts = System.currentTimeMillis()
        val name = "ArcisNVR_$ts.jpg"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ArcisNVR")
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            uri?.let { u ->
                context.contentResolver.openOutputStream(u)?.use { out ->
                    bmp.compress(Bitmap.CompressFormat.JPEG, 92, out)
                }
                // Flash + photo-saved overlay (replaces Toast)
                launch { flashAlpha.snapTo(0.85f); flashAlpha.animateTo(0f, tween(220)) }
                photoSaved = true
                delay(2_500)
                photoSaved = false
            }
        } else {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                .also { it.mkdirs() }
            val file = File(dir, name)
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            android.media.MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)
            launch { flashAlpha.snapTo(0.85f); flashAlpha.animateTo(0f, tween(220)) }
            photoSaved = true
            delay(2_500)
            photoSaved = false
        }
    }

    // Recording: start/stop VLC file output.
    LaunchedEffect(isRecording) {
        if (isRecording) {
            // VLC record() expects a DIRECTORY path — create it explicitly
            val base = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.cacheDir
            val recDir = java.io.File(base, "ArcisNVR").also { it.mkdirs() }
            recState.prefix = recDir.absolutePath
            player.record(recDir.absolutePath)
        } else {
            val dirPath = recState.prefix
            recState.prefix = null
            val dir = if (dirPath != null) java.io.File(dirPath)
                      else java.io.File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.cacheDir, "ArcisNVR")
            player.record(null)
            // Give VLC time to flush and close the file before we read it
            kotlinx.coroutines.delay(3_000)
            val videoExts = listOf("ts", "mp4", "mkv", "avi", "m4v")
            // Find the most recently modified video file that VLC saved to the directory
            val newest = dir.listFiles()
                ?.filter { f -> f.extension.lowercase() in videoExts && f.length() > 0 }
                ?.maxByOrNull { it.lastModified() }
            if (newest != null) {
                val mimeType = when (newest.extension.lowercase()) {
                    "mp4" -> "video/mp4"
                    "mkv" -> "video/x-matroska"
                    else  -> "video/mp2ts"
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Video.Media.DISPLAY_NAME, newest.name)
                        put(MediaStore.Video.Media.MIME_TYPE, mimeType)
                        put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ArcisNVR")
                        put(MediaStore.Video.Media.IS_PENDING, 1)
                    }
                    val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                    uri?.let { u ->
                        context.contentResolver.openOutputStream(u)?.use { out ->
                            newest.inputStream().use { it.copyTo(out) }
                        }
                        values.clear()
                        values.put(MediaStore.Video.Media.IS_PENDING, 0)
                        context.contentResolver.update(u, values, null, null)
                        newest.delete()
                    }
                } else {
                    val dest = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                        .also { it.mkdirs() }
                    val target = File(dest, newest.name)
                    newest.copyTo(target, overwrite = true)
                    newest.delete()
                    android.media.MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), null, null)
                }
                onRecordSaved?.invoke(newest.name)
                recordSavedLabel = newest.name
                recordSaved = true
                delay(3_000)
                recordSaved = false
            }
        }
    }

    // Capture a thumbnail every 15s while playing for the Device tab grid.
    // PixelCopy is a GPU readback — doing it too frequently causes micro-stutters.
    // We scale to ≤320 px wide so the bitmap is cheap to store and display.
    if (onThumbnail != null) {
        LaunchedEffect(playing, rtspUrl) {
            if (!playing) return@LaunchedEffect
            while (isActive) {
                delay(15_000)
                val bmp = suspendCancellableCoroutine<Bitmap?> { cont ->
                    val sv = findSurfaceView(videoLayout)
                    if (sv == null || sv.width <= 0 || sv.height <= 0) {
                        cont.resume(null); return@suspendCancellableCoroutine
                    }
                    val b = Bitmap.createBitmap(sv.width, sv.height, Bitmap.Config.RGB_565)
                    PixelCopy.request(sv, b, { result ->
                        if (cont.isActive) cont.resume(if (result == PixelCopy.SUCCESS) b else null)
                    }, Handler(Looper.getMainLooper()))
                } ?: continue
                // Scale down off the main thread to avoid blocking the compositor
                val scaled = withContext(kotlinx.coroutines.Dispatchers.Default) {
                    val maxW = 320
                    if (bmp.width <= maxW) bmp
                    else {
                        val scale = maxW.toFloat() / bmp.width
                        val w = maxW; val h = (bmp.height * scale).toInt()
                        Bitmap.createScaledBitmap(bmp, w, h, false).also { if (it !== bmp) bmp.recycle() }
                    }
                }
                onThumbnail(scaled)
            }
        }
    }

    // Attach views once; tear down when the composable leaves composition entirely.
    DisposableEffect(Unit) {
        player.attachViews(videoLayout, null, false, false)
        onDispose {
            player.stop()
            player.detachViews()
            player.setEventListener(null)
            player.release()
            // libVlc is a process-wide singleton — never release it per-player
        }
    }

    // Event listener remounts on retry/URL change so it captures the fresh state objects.
    DisposableEffect(rtspUrl, forceTcp, isRemote, retryEpoch) {
        val listener = MediaPlayer.EventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Opening          -> state = "Opening…"
                MediaPlayer.Event.Buffering        -> if (!playing) {
                    val pct = event.buffering.toInt()
                    state = if (pct == 0) "Connecting…" else "Buffering $pct%"
                }
                MediaPlayer.Event.Playing          -> { playing = true; state = "Playing" }
                MediaPlayer.Event.Paused           -> state = "Paused"
                MediaPlayer.Event.Stopped          -> state = "Stopped"
                MediaPlayer.Event.EndReached       -> state = "Stream ended"
                MediaPlayer.Event.EncounteredError -> error = "Playback failed."
            }
        }
        player.setEventListener(listener)
        onDispose { player.setEventListener(null) }
    }

    // Media creation + player.play() moved to IO thread so the composition thread
    // is never blocked. startDelayMs staggers grid channels to avoid concurrent
    // native decoder init (which caused the 4-channel ANR).
    LaunchedEffect(rtspUrl, forceTcp, isRemote, retryEpoch) {
        if (startDelayMs > 0L) kotlinx.coroutines.delay(startDelayMs)
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            player.stop()
            val media = Media(libVlc, android.net.Uri.parse(rtspUrl))
            media.setHWDecoderEnabled(true, false)
            if (forceTcp) media.addOption(":rtsp-tcp")
            player.media = media
            media.release()
            player.play()
        }
    }

    // Watchdog: auto-retry silently up to 3 times, then surface the error.
    // P2P streams can take 20-30s to start — the user should never have to
    // tap Retry unless all 3 attempts fail.
    LaunchedEffect(rtspUrl, forceTcp, isRemote, retryEpoch) {
        delay(if (isRemote) 22_000L else 7_000L)
        if (!playing && error == null) {
            if (autoRetries < 3) {
                autoRetries++   // persists across retryEpoch — resets only on URL change
                retryEpoch++    // triggers a new media-setup LaunchedEffect
            } else {
                error = if (isRemote)
                    "Stream timed out. Camera may be offline or the relay IPs need updating."
                else
                    "Stream unavailable. Check SD / HD or your connection."
            }
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView(modifier = Modifier.fillMaxSize(), factory = { videoLayout })

        // ── Screenshot flash ──────────────────────────────────────────────
        if (flashAlpha.value > 0f) {
            Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = flashAlpha.value)))
        }

        if (!playing) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (error == null) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(36.dp))
                    Spacer(Modifier.height(10.dp))
                    Text(
                        if (autoRetries > 0) "Reconnecting…" else state,
                        color = Color.White,
                    )
                } else {
                    Text("Stream unavailable", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Check your connection or try HD/SD.",
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { autoRetries = 0; retryEpoch++ },
                        shape = RoundedCornerShape(50),
                    ) { Text("Retry") }
                }
            }
        }

        // ── Recording timer badge (top-right) ────────────────────────────
        if (isRecording) {
            val infinite = rememberInfiniteTransition(label = "rec")
            val dotAlpha by infinite.animateFloat(
                initialValue    = 0.25f,
                targetValue     = 1f,
                animationSpec   = infiniteRepeatable(tween(600), RepeatMode.Reverse),
                label           = "dot",
            )
            Surface(
                shape  = RoundedCornerShape(4.dp),
                color  = Color.Black.copy(alpha = 0.60f),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .background(Color.Red.copy(alpha = dotAlpha), CircleShape)
                    )
                    Spacer(Modifier.width(5.dp))
                    val m = recordingElapsed / 60
                    val s = recordingElapsed % 60
                    Text("%02d:%02d".format(m, s), color = Color.White, fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold)
                }
            }
        }

        // ── Photo saved toast ─────────────────────────────────────────────
        AnimatedVisibility(
            visible  = photoSaved,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp),
            enter    = slideInVertically { it / 2 } + fadeIn(),
            exit     = slideOutVertically { it / 2 } + fadeOut(),
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color.Black.copy(alpha = 0.75f),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.CameraAlt, null,
                        tint = Color.White, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Photo saved", color = Color.White, fontSize = 13.sp,
                        fontWeight = FontWeight.Medium)
                }
            }
        }

        // ── Recording saved toast ─────────────────────────────────────────
        AnimatedVisibility(
            visible  = recordSaved,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp),
            enter    = slideInVertically { it / 2 } + fadeIn(),
            exit     = slideOutVertically { it / 2 } + fadeOut(),
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color.Black.copy(alpha = 0.75f),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.CheckCircle, null,
                        tint = Color(0xFF66BB6A), modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Recording saved", color = Color.White, fontSize = 13.sp,
                        fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

/** BFS through a ViewGroup hierarchy to find the first SurfaceView — used
 *  for PixelCopy thumbnail capture from VLCVideoLayout. */
private fun findSurfaceView(root: ViewGroup): SurfaceView? {
    val queue = ArrayDeque<View>().apply { add(root) }
    while (queue.isNotEmpty()) {
        val v = queue.removeFirst()
        if (v is SurfaceView) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) queue.add(v.getChildAt(i))
    }
    return null
}
