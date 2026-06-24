package com.arcisai.nvr.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arcisai.nvr.viewmodel.NvrViewModel

private data class EventPlayReq(val channel: Int, val beginSec: Long, val endSec: Long)

/**
 * Motion-events screen — launched from the Live tab's "Events" button.
 * Shows a motion-only recording timeline + ReplayPlayer for the selected channel.
 * If motion detection is disabled, shows a guidance card that deep-links to the
 * channel's Detection & Alerts settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventsTabScreen(
    channelId: Int,
    vm: NvrViewModel,
    onBack: () -> Unit,
    onNavigateToChannelSettings: (channelId: Int) -> Unit,
) {
    val creds = vm.credentials
    var channel by remember { mutableStateOf(channelId) }
    var dayMillis by remember { mutableStateOf(utcDayStart(localAsUtcNowMillis())) }
    var play by remember { mutableStateOf<EventPlayReq?>(null) }

    LaunchedEffect(creds) {
        if (creds != null) {
            vm.loadMotionDetection()
            vm.loadIpCamInfo()
            vm.loadConnectedChannels()
        }
    }

    val assigned = vm.channels.filter { it.ipAddr.isNotBlank() }.map { it.id }.sorted()
    LaunchedEffect(assigned) {
        if (assigned.isNotEmpty() && channel !in assigned) channel = assigned.first()
    }

    val configLoaded  = vm.motionDetectionCfg != null
    val motionEnabled = vm.isMotionEnabled(channel)

    LaunchedEffect(channel, dayMillis, creds, motionEnabled) {
        play = null
        if (creds != null && motionEnabled) vm.searchMotionEvents(channel, dayMillis)
    }

    val segments    = vm.motionEventSegments.orEmpty()
    val dayStartSec = dayMillis / 1000

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Events", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {

                // ── Player (16:9) ─────────────────────────────────────────────
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    val p = play
                    when {
                        !motionEnabled || !configLoaded -> {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.NotificationsOff, null,
                                    tint = Color.White.copy(alpha = 0.5f),
                                    modifier = Modifier.size(36.dp))
                                Spacer(Modifier.height(6.dp))
                                Text("Motion detection off",
                                    color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                            }
                        }
                        p == null -> {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.VideoLibrary, null,
                                    tint = Color.White.copy(alpha = 0.6f),
                                    modifier = Modifier.size(40.dp))
                                Spacer(Modifier.height(8.dp))
                                Text("Tap a motion event to preview",
                                    color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
                            }
                        }
                        creds != null -> {
                            val endpoint by produceState<Pair<String, Int>?>(null, p) {
                                value = vm.replayEndpoint()
                            }
                            val ep = endpoint
                            if (ep == null) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator(color = Color.White,
                                        modifier = Modifier.size(32.dp))
                                    Spacer(Modifier.height(8.dp))
                                    Text("Opening replay…", color = Color.White, fontSize = 12.sp)
                                }
                            } else {
                                key(p, ep) {
                                    ReplayPlayer(
                                        host = ep.first, port = ep.second,
                                        user = creds.username.ifBlank { "admin" },
                                        pass = creds.password,
                                        channel = p.channel,
                                        beginEpoch = p.beginSec,
                                        endEpoch   = p.endSec,
                                    )
                                }
                            }
                        }
                    }
                }

                // ── Channel + day picker ───────────────────────────────────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        fun label(id: Int) =
                            "Channel ${id + 1}" + if (vm.isChannelOffline(id)) "  ·  offline" else ""
                        DropdownSetting(
                            label   = "Channel",
                            value   = if (assigned.isEmpty()) "—" else label(channel),
                            options = assigned.map { label(it) },
                        ) { picked ->
                            val n = picked.removePrefix("Channel ").trimStart()
                                .takeWhile { it.isDigit() }.toIntOrNull()?.minus(1)
                            if (n != null && n in assigned) { channel = n; play = null }
                        }
                    }
                    IconButton(onClick = { dayMillis -= 86_400_000L; play = null }) {
                        Icon(Icons.Default.ChevronLeft, "Previous day")
                    }
                    Text(utcDayLabel(dayMillis), fontWeight = FontWeight.Medium, fontSize = 14.sp)
                    IconButton(
                        onClick  = { dayMillis += 86_400_000L; play = null },
                        enabled  = dayMillis < utcDayStart(localAsUtcNowMillis()),
                    ) { Icon(Icons.Default.ChevronRight, "Next day") }
                }

                // ── Motion detection disabled — guidance ───────────────────────
                if (!configLoaded) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    return@Column
                }

                if (!motionEnabled) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            modifier = Modifier.padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(
                                Icons.Default.NotificationsOff,
                                contentDescription = null,
                                tint     = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(56.dp),
                            )
                            Spacer(Modifier.height(16.dp))
                            Text(
                                "Motion detection is disabled for Channel ${channel + 1}",
                                fontSize    = 15.sp,
                                fontWeight  = FontWeight.SemiBold,
                                textAlign   = TextAlign.Center,
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Enable motion detection in channel settings to view motion events and the timeline here.",
                                fontSize  = 13.sp,
                                color     = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(Modifier.height(24.dp))
                            Button(onClick = { onNavigateToChannelSettings(channel) }) {
                                Icon(Icons.Default.Settings, null,
                                    modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Enable in Channel Settings")
                            }
                        }
                    }
                    return@Column
                }

                // ── Motion-only timeline ───────────────────────────────────────
                if (creds != null && !vm.motionEventBusy && segments.isNotEmpty()) {
                    RecordingTimeline(
                        dayStartSec = dayStartSec,
                        segments    = segments,
                        seekToSec   = play?.beginSec,
                    ) { tappedSec ->
                        val containing = segments.firstOrNull { tappedSec in it.startSec..it.endSec }
                        val next = segments.filter { it.startSec >= tappedSec }
                            .minByOrNull { it.startSec }
                        val seg = containing ?: next
                        if (seg != null) {
                            val begin = if (containing != null) tappedSec else seg.startSec
                            play = EventPlayReq(seg.channel, begin, seg.endSec)
                        }
                    }
                }

                HorizontalDivider()

                // ── Event list ────────────────────────────────────────────────
                when {
                    creds == null -> EventNote("Not connected.")
                    assigned.isEmpty() && vm.ipCamInfoLoading ->
                        Box(Modifier.fillMaxWidth().padding(32.dp),
                            contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    assigned.isEmpty() -> EventNote("No cameras configured.")
                    vm.motionEventBusy ->
                        Box(Modifier.fillMaxWidth().padding(32.dp),
                            contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    vm.motionEventStatus != null -> EventNote(vm.motionEventStatus!!)
                    else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(segments) { s ->
                            val isActive = play?.let { it.beginSec == s.startSec && it.channel == s.channel } == true
                            ListItem(
                                headlineContent = {
                                    Text(
                                        "${utcTime(s.startSec)} – ${utcTime(s.endSec)}",
                                        fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                                    )
                                },
                                supportingContent = {
                                    val secs = (s.endSec - s.startSec).coerceAtLeast(0)
                                    Text("Ch ${s.channel + 1}  ·  Motion  ·  ${secs}s",
                                        fontSize = 12.sp)
                                },
                                leadingContent = {
                                    Icon(
                                        Icons.Default.DirectionsRun,
                                        contentDescription = null,
                                        tint = if (isActive) MaterialTheme.colorScheme.error
                                               else MaterialTheme.colorScheme.primary,
                                    )
                                },
                                colors = if (isActive) ListItemDefaults.colors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                ) else ListItemDefaults.colors(),
                                modifier = Modifier.clickable {
                                    play = EventPlayReq(s.channel, s.startSec, s.endSec)
                                },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EventNote(text: String) {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
