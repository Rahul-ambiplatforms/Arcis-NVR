package com.arcisai.nvr.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
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
import com.arcisai.nvr.net.WsTalkbackClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
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
import android.widget.Toast
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import java.io.File

private enum class ViewMode { GRID, SINGLE_FULL, GRID_FULL }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveScreen(
    viewModel: NvrViewModel,
    channelId: Int,
    onBack: () -> Unit,
    onNavigateToPlayback: () -> Unit = {},
    onNavigateToSettings: (channelId: Int) -> Unit = {},
) {
    LaunchedEffect(Unit) { viewModel.loadIpCamInfo() }

    var selectedChannel by remember { mutableIntStateOf(channelId) }
    var viewMode by remember { mutableStateOf(ViewMode.GRID) }
    var useSub by remember { mutableStateOf(true) }
    var forceTcp by remember { mutableStateOf(true) }
    var audioMuted by remember { mutableStateOf(true) }
    var scale by remember { mutableFloatStateOf(1f) }
    var showMoreSheet by remember { mutableStateOf(false) }
    var showPresets by remember { mutableStateOf(false) }
    var ptzSpeed by remember { mutableIntStateOf(4) }
    var singleChannelView by remember { mutableStateOf(false) }
    var snapshotTrigger by remember { mutableIntStateOf(0) }
    var isRecording by remember { mutableStateOf(false) }
    val activity = LocalContext.current as? Activity
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

    val deviceName = viewModel.displayNvrName

    LaunchedEffect(selectedChannel) {
        viewModel.selectedLiveChannel = selectedChannel
        while (true) { viewModel.loadConnectedChannels(); kotlinx.coroutines.delay(8_000) }
    }

    LaunchedEffect(selectedChannel, useSub, boundIp, retryToken, offline) {
        fetchExhausted = false
        rtsp = null
        if (!assigned) return@LaunchedEffect
        if (offline && !userForced) return@LaunchedEffect
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

    // Back: fullscreen → grid, grid → caller
    BackHandler {
        when (viewMode) {
            ViewMode.SINGLE_FULL, ViewMode.GRID_FULL -> { viewMode = ViewMode.GRID; scale = 1f }
            ViewMode.GRID -> onBack()
        }
    }

    // Lock to landscape in fullscreen, revert when back to grid
    LaunchedEffect(viewMode) {
        activity?.requestedOrientation = when (viewMode) {
            ViewMode.GRID -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            else          -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
    }

    // ── FULLSCREEN overlay (hides Scaffold entirely) ─────────────────────────
    if (viewMode != ViewMode.GRID) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (viewMode == ViewMode.SINGLE_FULL) {
                val transformState = rememberTransformableState { zoom, _, _ ->
                    scale = (scale * zoom).coerceIn(1f, 4f)
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        .transformable(transformState)
                        .graphicsLayer { scaleX = scale; scaleY = scale },
                ) {
                    ChannelContent(
                        modifier = Modifier.fillMaxSize(),
                        rtsp = rtsp, fetchExhausted = fetchExhausted,
                        assigned = assigned, offline = offline, userForced = userForced,
                        forceTcp = forceTcp, remoteMode = remoteMode, audioMuted = audioMuted,
                        onUserForced = { userForced = true; retryToken++ },
                        onRetry = { retryToken++ },
                        onThumbnail = { bmp -> viewModel.setChannelThumbnail(selectedChannel, bmp) },
                        snapshotTrigger = snapshotTrigger,
                        isRecording = isRecording,
                        onRecordSaved = { name ->
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar("Saved: $name")
                            }
                        },
                    )
                }
            } else {
                // GRID_FULL
                LiveChannelGrid(
                    modifier = Modifier.fillMaxSize(),
                    channels = viewModel.channels.take(4),
                    selectedChannel = selectedChannel,
                    connectedChannels = viewModel.connectedChannels,
                    channelStatus = viewModel.channelStatus,
                    forceTcp = forceTcp,
                    useSub = useSub,
                    audioMuted = audioMuted,
                    viewModel = viewModel,
                    onChannelTap = { selectedChannel = it },
                    onThumbnail = { bmp -> viewModel.setChannelThumbnail(selectedChannel, bmp) },
                )
            }
            IconButton(
                onClick = { viewMode = ViewMode.GRID; scale = 1f },
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            ) {
                Icon(Icons.Default.FullscreenExit, "Exit fullscreen", tint = Color.White)
            }
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
        return
    }

    // ── NORMAL (GRID) view ────────────────────────────────────────────────────
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(deviceName, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { onNavigateToSettings(selectedChannel) }) {
                        Icon(Icons.Default.Settings, "Settings")
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
            LiveBottomBar(
                selectedChannel = selectedChannel,
                creds = viewModel.credentials,
                onDevice = onBack,
                onEvents = { /* TODO: events screen */ },
                showPresets = showPresets,
                onPresetsToggle = { showPresets = !showPresets },
                onMore = { showMoreSheet = true },
            )
        },
        containerColor = Color.Black,
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {

            // ── 1-channel or 4-channel grid ──────────────────────────────────
            if (singleChannelView) {
                ChannelContent(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    rtsp = rtsp, fetchExhausted = fetchExhausted,
                    assigned = assigned, offline = offline, userForced = userForced,
                    forceTcp = forceTcp, remoteMode = remoteMode, audioMuted = audioMuted,
                    onUserForced = { userForced = true; retryToken++ },
                    onRetry = { retryToken++ },
                    onThumbnail = { bmp -> viewModel.setChannelThumbnail(selectedChannel, bmp) },
                    snapshotTrigger = snapshotTrigger,
                    isRecording = isRecording,
                    onRecordSaved = { name ->
                        coroutineScope.launch { snackbarHostState.showSnackbar("Saved: $name") }
                    },
                )
            } else {
                LiveChannelGrid(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    channels = viewModel.channels.take(4),
                    selectedChannel = selectedChannel,
                    connectedChannels = viewModel.connectedChannels,
                    channelStatus = viewModel.channelStatus,
                    forceTcp = forceTcp,
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
            }

            // ── Controls bar ─────────────────────────────────────────────────
            VideoControlsBar(
                useSub = useSub,
                audioMuted = audioMuted,
                singleChannelView = singleChannelView,
                isRecording = isRecording,
                onQualityToggle = { useSub = !useSub },
                onGridToggle = { singleChannelView = !singleChannelView },
                onAudioToggle = { audioMuted = !audioMuted },
                onSnapshot = { snapshotTrigger++ },
                onRecord = { isRecording = !isRecording },
                onFullscreen = { viewMode = ViewMode.SINGLE_FULL; scale = 1f },
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
            onDismiss = { showMoreSheet = false },
            onSettings = { showMoreSheet = false; onNavigateToSettings(selectedChannel) },
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
                        Text("Playback Error", color = Color.White, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        Text(
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
                    rtspUrl = rtsp, forceTcp = forceTcp, audioMuted = audioMuted,
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
    useSub: Boolean,
    audioMuted: Boolean,
    viewModel: NvrViewModel,
    onChannelTap: (Int) -> Unit,
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

        // ── 2×2 grid ──────────────────────────────────────────────────────────
        val rows = channels.chunked(2)
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
                                    useSub = useSub,
                                    audioMuted = true,
                                    viewModel = viewModel,
                                    onTap = { onChannelTap(ch.id) },
                                    onDoubleTap = {
                                        onChannelTap(ch.id)
                                        overlayChannel = ch.id
                                        overlayScale = 1f
                                    },
                                    onThumbnail = { bmp -> viewModel.setChannelThumbnail(ch.id, bmp) },
                                    snapshotTrigger = if (ch.id == selectedChannel) snapshotTrigger else 0,
                                    isRecording = ch.id == selectedChannel && isRecording,
                                    onRecordSaved = if (ch.id == selectedChannel) onRecordSaved else null,
                                )
                            }
                        }
                    }
                    if (row.size < 2) {
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

    if (assigned && !knownOffline) {
        LaunchedEffect(ch.id, ch.ipAddr, useSub) {
            rtsp = null
            val stream = if (useSub) 1 else 0
            var attempts = 0
            while (rtsp == null && attempts < 3) {
                attempts++
                rtsp = viewModel.ensureChannelStreamUrl(ch.id, stream = stream)
                if (rtsp == null && attempts < 3) delay(1_500L * attempts)
            }
        }
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
                    audioMuted = audioMuted,
                    onThumbnail = onThumbnail,
                    snapshotTrigger = snapshotTrigger,
                    isRecording = isRecording,
                    onRecordSaved = onRecordSaved,
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
    onQualityToggle: () -> Unit,
    onGridToggle: () -> Unit,
    onAudioToggle: () -> Unit,
    onSnapshot: () -> Unit,
    onRecord: () -> Unit,
    onFullscreen: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF181818))
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Quality chip
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
        // Toggle 1-channel / 4-channel
        ControlIcon(
            if (singleChannelView) Icons.Default.GridView else Icons.Default.CropLandscape,
            if (singleChannelView) "4-channel" else "1-channel",
            onGridToggle,
        )
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
        // Single fullscreen
        ControlIcon(Icons.Default.Fullscreen, "Fullscreen", onFullscreen)
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
    onDevice: () -> Unit,
    onEvents: () -> Unit,
    showPresets: Boolean,
    onPresetsToggle: () -> Unit,
    onMore: () -> Unit,
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
                host = creds?.host ?: "",
                port = 10000,
                username = creds?.username ?: "admin",
                password = creds?.password ?: "",
                channel = selectedChannel,
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

    Box(
        modifier = Modifier
            .size(48.dp)
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
                                    onError = { /* stop silently on error */ },
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
        Icon(Icons.Default.Mic, "Talk", tint = Color.White, modifier = Modifier.size(22.dp))
    }
}

// ─── More features sheet ─────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoreFeaturesSheet(
    onDismiss: () -> Unit,
    onSettings: () -> Unit,
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

        val features = listOf(
            Pair(Icons.Default.Autorenew,           "Auto Cruise"),
            Pair(Icons.Default.Adjust,              "PTZ Calibration"),
            Pair(Icons.Default.NightsStay,          "Night Vision"),
            Pair(Icons.Default.NotificationsActive, "Siren"),
            Pair(Icons.Default.Image,               "Photos"),
            Pair(Icons.Default.Share,               "Device Sharing"),
        )

        features.chunked(4).forEach { rowItems ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.Start,
            ) {
                rowItems.forEach { (icon, label) ->
                    FeatureItem(icon, label, Modifier.weight(1f))
                }
                // Fill remaining columns
                repeat(4 - rowItems.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun FeatureItem(icon: ImageVector, label: String, modifier: Modifier) {
    Column(
        modifier = modifier
            .clickable { }
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.size(52.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, null, modifier = Modifier.size(24.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(label, fontSize = 11.sp, textAlign = TextAlign.Center, maxLines = 2)
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
    audioMuted: Boolean = false,
    onThumbnail: ((Bitmap) -> Unit)? = null,
    snapshotTrigger: Int = 0,
    isRecording: Boolean = false,
    onRecordSaved: ((String) -> Unit)? = null,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var retryEpoch by remember(rtspUrl, forceTcp) { mutableIntStateOf(0) }
    var state by remember(rtspUrl, forceTcp, retryEpoch) { mutableStateOf("Connecting…") }
    var error by remember(rtspUrl, forceTcp, retryEpoch) { mutableStateOf<String?>(null) }
    var playing by remember(rtspUrl, forceTcp, retryEpoch) { mutableStateOf(false) }

    val libVlc = remember(forceTcp, retryEpoch) {
        LibVLC(context, arrayListOf(
            "--no-drop-late-frames",
            "--no-skip-frames",
            "--rtsp-frame-buffer-size=1100000",
            "--network-caching=300",
            "--live-caching=300",
            "--clock-jitter=0",
            "--clock-synchro=0",
            if (forceTcp) "--rtsp-tcp" else "--no-rtsp-tcp",
            if (com.arcisai.nvr.BuildConfig.DEBUG) "-vvv" else "-q",
        ))
    }
    val player = remember(libVlc) { MediaPlayer(libVlc) }
    val videoLayout = remember(libVlc) { VLCVideoLayout(context) }

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
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    Toast.makeText(context, "Snapshot saved", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                .also { it.mkdirs() }
            val file = File(dir, name)
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            android.media.MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                Toast.makeText(context, "Snapshot saved", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Recording: start/stop VLC file output
    LaunchedEffect(isRecording) {
        if (isRecording) {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
                ?: context.cacheDir
            dir.mkdirs()
            player.record(dir.absolutePath)
        } else {
            // Stop recording — pass null to end it
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
                ?: context.cacheDir
            // Find newest file created in that dir
            val before = dir.listFiles()?.map { it.name to it.lastModified() }?.toMap() ?: emptyMap()
            player.record(null)
            kotlinx.coroutines.delay(500)
            val newest = dir.listFiles()
                ?.filter { !before.containsKey(it.name) || it.lastModified() > (before[it.name] ?: 0L) }
                ?.maxByOrNull { it.lastModified() }
            if (newest != null) {
                // Copy into MediaStore Movies
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Video.Media.DISPLAY_NAME, newest.name)
                        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                        put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ArcisNVR")
                    }
                    val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                    uri?.let { u ->
                        context.contentResolver.openOutputStream(u)?.use { out ->
                            newest.inputStream().use { it.copyTo(out) }
                        }
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
            }
        }
    }

    // Capture a thumbnail every 5s while playing, so the Device tab grid
    // can show the last-seen frame for this channel.
    if (onThumbnail != null) {
        LaunchedEffect(playing, rtspUrl) {
            if (!playing) return@LaunchedEffect
            while (isActive) {
                delay(5_000)
                val bmp = suspendCancellableCoroutine<Bitmap?> { cont ->
                    val sv = findSurfaceView(videoLayout)
                    if (sv == null || sv.width <= 0 || sv.height <= 0) {
                        cont.resume(null); return@suspendCancellableCoroutine
                    }
                    val b = Bitmap.createBitmap(sv.width, sv.height, Bitmap.Config.ARGB_8888)
                    PixelCopy.request(sv, b, { result ->
                        if (cont.isActive) cont.resume(if (result == PixelCopy.SUCCESS) b else null)
                    }, Handler(Looper.getMainLooper()))
                }
                bmp?.let { onThumbnail(it) }
            }
        }
    }

    DisposableEffect(rtspUrl, forceTcp, retryEpoch) {
        val listener = MediaPlayer.EventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Opening          -> state = "Opening…"
                MediaPlayer.Event.Buffering        -> if (!playing) state = "Buffering ${event.buffering.toInt()}%"
                MediaPlayer.Event.Playing          -> { playing = true; state = "Playing" }
                MediaPlayer.Event.Paused           -> state = "Paused"
                MediaPlayer.Event.Stopped          -> state = "Stopped"
                MediaPlayer.Event.EndReached       -> state = "Stream ended"
                MediaPlayer.Event.EncounteredError -> error = "Playback failed."
            }
        }
        player.setEventListener(listener)
        player.attachViews(videoLayout, null, false, false)

        val media = Media(libVlc, android.net.Uri.parse(rtspUrl))
        media.setHWDecoderEnabled(true, false)
        media.addOption(":network-caching=300")
        media.addOption(":live-caching=300")
        if (forceTcp) media.addOption(":rtsp-tcp")
        player.media = media
        media.release()
        player.play()

        onDispose {
            player.stop()
            player.detachViews()
            player.setEventListener(null)
            player.release()
            libVlc.release()
        }
    }

    // Watchdog: surface Retry after 12s of no playback
    LaunchedEffect(rtspUrl, forceTcp, retryEpoch) {
        delay(12_000)
        if (!playing && error == null)
            error = "Stream timed out. Try switching SD / HD."
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView(modifier = Modifier.fillMaxSize(), factory = { videoLayout })
        if (!playing) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (error == null) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(36.dp))
                    Spacer(Modifier.height(10.dp))
                    Text(state, color = Color.White)
                } else {
                    Text("Playback Error", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        error ?: "Try again.",
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { retryEpoch++ },
                        shape = RoundedCornerShape(50),
                    ) { Text("Retry") }
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
