package com.arcisai.nvr.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arcisai.nvr.ui.theme.ArcisGreen
import com.arcisai.nvr.viewmodel.NvrViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelSettingsScreen(
    vm: NvrViewModel,
    channelId: Int,
    onBack: () -> Unit,
    onNavigate: (String) -> Unit,
    onOpenLive: (channelId: Int) -> Unit = {},
    onOpenNightMode: (channelId: Int) -> Unit = {},
) {
    val ch        = vm.channels.getOrNull(channelId)
    val isOnline  = vm.connectedChannels?.contains(channelId) == true
    val clipboard = LocalClipboardManager.current

    var displayName  by remember(channelId) { mutableStateOf(vm.channelDisplayName(channelId)) }
    var renameDraft  by remember { mutableStateOf("") }
    var showRename   by remember { mutableStateOf(false) }
    var showDelete   by remember { mutableStateOf(false) }
    var audioGain    by remember(channelId) { mutableFloatStateOf(vm.channelAudioGain(channelId)) }
    var gainDraft    by remember { mutableFloatStateOf(1.0f) }
    var showVolumeDialog by remember { mutableStateOf(false) }

    val bg      = MaterialTheme.colorScheme.background
    val surface = MaterialTheme.colorScheme.surface
    val label   = MaterialTheme.colorScheme.onSurfaceVariant
    val divClr  = MaterialTheme.colorScheme.outlineVariant
    val chevron = MaterialTheme.colorScheme.outline
    val primary = MaterialTheme.colorScheme.primary

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("Channel ${channelId + 1}",
                        fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = bg),
            )
        },
        containerColor = bg,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(bottom = 48.dp),
        ) {

            // ── Camera card ───────────────────────────────────────────────
            item {
                Card(
                    modifier  = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    shape     = RoundedCornerShape(14.dp),
                    colors    = CardDefaults.cardColors(containerColor = surface),
                    elevation = CardDefaults.cardElevation(0.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Camera avatar with online indicator
                        Box(modifier = Modifier.size(72.dp)) {
                            Box(
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        if (isOnline) primary.copy(alpha = 0.12f)
                                        else MaterialTheme.colorScheme.surfaceVariant
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Default.Videocam, null,
                                    tint     = if (isOnline) primary else label.copy(alpha = 0.5f),
                                    modifier = Modifier.size(34.dp),
                                )
                            }
                            // Online dot
                            Box(
                                modifier = Modifier
                                    .size(14.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surface)
                                    .align(Alignment.BottomEnd),
                                contentAlignment = Alignment.Center,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(if (isOnline) ArcisGreen else Color(0xFFBDBDBD)),
                                )
                            }
                        }

                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    displayName,
                                    fontWeight = FontWeight.Bold, fontSize = 16.sp,
                                    maxLines   = 1, overflow = TextOverflow.Ellipsis,
                                    modifier   = Modifier.weight(1f, fill = false),
                                )
                                Spacer(Modifier.width(6.dp))
                                Icon(Icons.Default.Edit, "Rename", tint = label,
                                    modifier = Modifier.size(16.dp).clickable {
                                        renameDraft = displayName; showRename = true
                                    })
                            }
                            Spacer(Modifier.height(3.dp))
                            Text(
                                if (isOnline) "Online" else "Offline",
                                fontSize = 12.sp,
                                color    = if (isOnline) ArcisGreen else MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.Medium,
                            )
                            if (ch != null && ch.modelName.isNotBlank()) {
                                Spacer(Modifier.height(3.dp))
                                Text(ch.modelName, fontSize = 12.sp, color = label, maxLines = 1)
                            }
                            if (ch != null && ch.ipAddr.isNotBlank()) {
                                Spacer(Modifier.height(2.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(ch.ipAddr, fontSize = 12.sp, color = label,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false))
                                    Spacer(Modifier.width(4.dp))
                                    Icon(Icons.Default.ContentCopy, "Copy IP", tint = label,
                                        modifier = Modifier.size(13.dp).clickable {
                                            clipboard.setText(AnnotatedString(ch.ipAddr))
                                        })
                                }
                            }
                        }
                    }
                }
            }

            // ── Live View quick-access ────────────────────────────────────
            item {
                ChGroup(surface) {
                    ChIconNavRow(
                        icon    = Icons.Default.PlayCircle,
                        iconBg  = Color(0xFF1E88E5),
                        title   = "Open Live View",
                        label   = label, chevron = chevron,
                        onClick = { onOpenLive(channelId) },
                    )
                }
                Spacer(Modifier.height(20.dp))
            }

            // ── Camera Vision Mode ────────────────────────────────────────
            item { ChSectionHeader("Camera Vision Mode") }
            item {
                ChGroup(surface) {
                    ChIconNavRow(
                        icon    = Icons.Default.NightsStay,
                        iconBg  = Color(0xFF4527A0),
                        title   = "Camera Vision Mode",
                        subtitle = "Day, night or auto IR-cut scheduling",
                        label   = label, chevron = chevron,
                        onClick = { onOpenNightMode(channelId) },
                    )
                }
                Spacer(Modifier.height(20.dp))
            }

            // ── PTZ Control ───────────────────────────────────────────────
            item { ChSectionHeader("PTZ Control") }
            item {
                ChGroup(surface) {
                    ChIconNavRow(
                        icon    = Icons.Default.Games,
                        iconBg  = Color(0xFF00897B),
                        title   = "PTZ Control",
                        subtitle = "Pan, tilt, zoom and presets",
                        label   = label, chevron = chevron,
                        onClick = { onOpenLive(channelId) },
                    )
                }
                Spacer(Modifier.height(20.dp))
            }

            // ── Events ────────────────────────────────────────────────────
            item { ChSectionHeader("Events") }
            item {
                ChGroup(surface) {
                    ChIconNavRow(
                        icon    = Icons.AutoMirrored.Filled.DirectionsRun,
                        iconBg  = Color(0xFFE53935),
                        title   = "Detection & Alerts",
                        subtitle = "Motion, human tracking & push notifications",
                        label   = label, chevron = chevron,
                        onClick = { onNavigate("channel-motion/$channelId") },
                    )
                }
                Spacer(Modifier.height(20.dp))
            }

            // ── Device Settings ───────────────────────────────────────────
            item { ChSectionHeader("Device Settings") }
            item {
                ChGroup(surface) {
                    ChIconNavRow(
                        icon    = Icons.Default.Tune,
                        iconBg  = Color(0xFF039BE5),
                        title   = "Image & Color",
                        subtitle = "Brightness, contrast, saturation",
                        label   = label, chevron = chevron,
                        onClick = { onNavigate("channel-color/$channelId") },
                    )
                    ChDividerRow(divClr)
                    ChIconNavRow(
                        icon    = Icons.Default.Speed,
                        iconBg  = Color(0xFF43A047),
                        title   = "Stream Quality",
                        subtitle = "Resolution and bitrate",
                        label   = label, chevron = chevron,
                        onClick = { onNavigate("channel-encode/$channelId") },
                    )
                    ChDividerRow(divClr)
                    ChIconNavRow(
                        icon    = Icons.AutoMirrored.Filled.VolumeUp,
                        iconBg  = Color(0xFFE65100),
                        title   = "Camera Audio Volume",
                        subtitle = "Speaker volume for live audio",
                        value   = "${(audioGain * 100).toInt()}%",
                        label   = label, chevron = chevron,
                        onClick = { gainDraft = audioGain; showVolumeDialog = true },
                    )
                }
                Spacer(Modifier.height(20.dp))
            }

            // ── Advanced ──────────────────────────────────────────────────
            item { ChSectionHeader("Advanced") }
            item {
                ChGroup(surface) {
                    ChIconNavRow(
                        icon    = Icons.Default.TextFields,
                        iconBg  = Color(0xFF757575),
                        title   = "OSD Overlay",
                        subtitle = "Channel name & timestamp on video",
                        label   = label, chevron = chevron,
                        onClick = { onNavigate("channel-osd/$channelId") },
                    )
                }
                Spacer(Modifier.height(20.dp))
            }

            // ── About ─────────────────────────────────────────────────────
            item { ChSectionHeader("About") }
            item {
                ChGroup(surface) {
                    ChIconNavRow(
                        icon    = Icons.Default.Info,
                        iconBg  = Color(0xFF1E88E5),
                        title   = "About Channel",
                        subtitle = "Model, type, network info",
                        label   = label, chevron = chevron,
                        onClick = { onNavigate("channel-about/$channelId") },
                    )
                }
            }

            // ── Danger zone ───────────────────────────────────────────────
            item { Spacer(Modifier.height(32.dp)) }
            item {
                Button(
                    onClick  = { showDelete = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .height(50.dp),
                    shape  = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Icon(Icons.Default.Delete, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Delete Channel", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }

    // ── Rename dialog ──────────────────────────────────────────────────────────
    if (showRename) {
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text("Rename Device") },
            text  = {
                OutlinedTextField(
                    value         = renameDraft,
                    onValueChange = { renameDraft = it },
                    singleLine    = true,
                    label         = { Text("Device name") },
                    modifier      = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (renameDraft.isNotBlank()) {
                        displayName = renameDraft.trim()
                        vm.setChannelDisplayName(channelId, renameDraft.trim())
                    }
                    showRename = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showRename = false }) { Text("Cancel") }
            },
        )
    }

    // ── Delete confirm ─────────────────────────────────────────────────────────
    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("Delete Channel?") },
            text  = { Text("This will remove Channel ${channelId + 1} from the NVR.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.saveIpCamEntry(
                        channelId,
                        mapOf(
                            "IPAddr" to "", "Username" to "", "Password" to "",
                            "Modelname" to "", "Enable" to "False",
                        ),
                    )
                    showDelete = false
                    onBack()
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = false }) { Text("Cancel") }
            },
        )
    }

    // ── Audio Volume dialog ────────────────────────────────────────────────────
    if (showVolumeDialog) {
        AlertDialog(
            onDismissRequest = { showVolumeDialog = false },
            title = { Text("Camera Audio Volume") },
            text = {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            "${(gainDraft * 100).toInt()}%",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Slider(
                        value = gainDraft,
                        onValueChange = { gainDraft = it },
                        valueRange = 0f..1f,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("0%", fontSize = 11.sp, color = label)
                        Text("100%", fontSize = 11.sp, color = label)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    audioGain = gainDraft
                    vm.setChannelAudioGain(channelId, gainDraft)
                    showVolumeDialog = false
                }) { Text("Apply") }
            },
            dismissButton = {
                TextButton(onClick = { showVolumeDialog = false }) { Text("Cancel") }
            },
        )
    }
}

// ─── Private composables ──────────────────────────────────────────────────────

@Composable
private fun ChGroup(surface: Color, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier  = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape     = RoundedCornerShape(12.dp),
        colors    = CardDefaults.cardColors(containerColor = surface),
        elevation = CardDefaults.cardElevation(0.dp),
    ) { Column(content = content) }
}

@Composable
private fun ChSectionHeader(text: String) {
    Text(
        text,
        modifier = Modifier.fillMaxWidth()
            .padding(start = 20.dp, end = 16.dp, top = 4.dp, bottom = 7.dp),
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        color    = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ChIconNavRow(
    icon: ImageVector,
    iconBg: Color,
    title: String,
    subtitle: String? = null,
    value: String? = null,
    label: Color,
    chevron: Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Rounded icon badge
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(iconBg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp)
            if (subtitle != null) {
                Text(subtitle, fontSize = 12.sp, color = label, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
        }
        if (value != null) {
            Text(value, fontSize = 14.sp, color = label)
            Spacer(Modifier.width(4.dp))
        }
        Icon(Icons.AutoMirrored.Filled.ArrowForwardIos, null,
            tint = chevron, modifier = Modifier.size(13.dp))
    }
}

@Composable
private fun ChDividerRow(divClr: Color) {
    HorizontalDivider(
        modifier  = Modifier.padding(start = 60.dp),
        thickness = 0.5.dp,
        color     = divClr,
    )
}
