package com.arcisai.nvr.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.*
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arcisai.nvr.ui.theme.ArcisGray
import com.arcisai.nvr.ui.theme.ArcisGreen
import com.arcisai.nvr.viewmodel.ChannelInfo
import com.arcisai.nvr.viewmodel.NvrViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveTabScreen(
    vm: NvrViewModel,
    onChannelTap: (Int) -> Unit,
    onOpenSettings: () -> Unit = {},
    onOpenPlayback: () -> Unit = {},
    onDisconnect: () -> Unit = {},
) {
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                vm.refreshChannels()
                vm.loadIpCamInfo()
                vm.loadConnectedChannels()
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(Unit) {
        while (true) { vm.loadConnectedChannels(); kotlinx.coroutines.delay(8000) }
    }

    var displayName by rememberSaveable { mutableStateOf(vm.displayNvrName) }
    LaunchedEffect(displayName) { vm.setDisplayNvrName(displayName) }

    var showMenu by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var editDraft by remember { mutableStateOf("") }

    val onRefresh = { vm.refreshChannels(); vm.loadIpCamInfo(); vm.loadConnectedChannels(); Unit }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        // Page header
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                "Device",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
        when {
            vm.channels.isEmpty() && vm.channelsError == null ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            vm.channelsError != null && vm.channels.isEmpty() ->
                ErrorBlock(vm.channelsError!!) { vm.refreshChannels(); vm.loadIpCamInfo() }
            else ->
                DeviceCard(
                    displayName = displayName,
                    channels = vm.channels,
                    showMenu = showMenu,
                    onMenuOpen = { showMenu = true },
                    onMenuDismiss = { showMenu = false },
                    onRefresh = onRefresh,
                    onMenuEdit = { editDraft = displayName; showEditDialog = true },
                    onMenuSettings = onOpenSettings,
                    onMenuDelete = { showDeleteConfirm = true },
                    onChannelTap = onChannelTap,
                    onPlaybackTap = onOpenPlayback,
                )
        }

        // NVR offline banner — floats at the bottom of the inner Box
        if (!vm.nvrOnline) {
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.95f),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "NVR offline — reconnecting…",
                        modifier = Modifier.weight(1f),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    TextButton(onClick = { vm.reconnectNow() }) { Text("Retry") }
                }
            }
        }
        } // end inner Box
    } // end Column

    if (showEditDialog) {
        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            title = { Text("Rename NVR") },
            text = {
                OutlinedTextField(
                    value = editDraft,
                    onValueChange = { editDraft = it },
                    label = { Text("NVR name") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val saved = editDraft.trim().ifBlank { "NVR Device" }
                    displayName = saved
                    vm.setDisplayNvrName(saved)
                    showEditDialog = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showEditDialog = false }) { Text("Cancel") }
            },
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Remove NVR") },
            text = { Text("This will disconnect from \"${displayName}\". You can reconnect by logging in again.") },
            confirmButton = {
                TextButton(onClick = { showDeleteConfirm = false; onDisconnect() }) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            },
        )
    }
}

// ─── Device card (matches reference app card layout) ────────────────────────

@Composable
private fun DeviceCard(
    displayName: String,
    channels: List<ChannelInfo>,
    showMenu: Boolean,
    onMenuOpen: () -> Unit,
    onMenuDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onMenuEdit: () -> Unit,
    onMenuSettings: () -> Unit,
    onMenuDelete: () -> Unit,
    onChannelTap: (Int) -> Unit,
    @Suppress("UNUSED_PARAMETER") onPlaybackTap: () -> Unit,
) {
    val tapChannel = channels.firstOrNull { it.ipAddr.isNotBlank() }?.id ?: 0

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 12.dp),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column {
            // ── Card header: device name + refresh + 3-dot menu ───────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    displayName,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = onRefresh, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "Refresh",
                        modifier = Modifier.size(18.dp),
                    )
                }
                Box {
                    IconButton(onClick = onMenuOpen, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More options")
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = onMenuDismiss) {
                        DropdownMenuItem(
                            text = { Text("Settings") },
                            leadingIcon = { Icon(Icons.Default.Settings, null) },
                            onClick = { onMenuDismiss(); onMenuSettings() },
                        )
                        DropdownMenuItem(
                            text = { Text("Edit") },
                            leadingIcon = { Icon(Icons.Default.Edit, null) },
                            onClick = { onMenuDismiss(); onMenuEdit() },
                        )
                        DropdownMenuItem(
                            text = { Text("Delete") },
                            leadingIcon = { Icon(Icons.Default.Delete, null) },
                            onClick = { onMenuDismiss(); onMenuDelete() },
                        )
                    }
                }
            }

            // Channel-count subtitle — how many slots have a camera assigned.
            Text(
                "${channels.count { it.ipAddr.isNotBlank() }} of ${channels.size} channels",
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 8.dp),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // ── Single device preview — no per-channel loading, tap to go live ──
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(Color(0xFF0D0A1C))
                    .clickable { onChannelTap(tapChannel) },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.52f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = "Open live view",
                            tint = Color.White,
                            modifier = Modifier.size(34.dp),
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Tap to view live",
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.75f),
                    )
                }
            }

        }
    }
}

// ─── Card action button ──────────────────────────────────────────────────────

@Composable
private fun CardActionButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 28.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            icon, null,
            modifier = Modifier.size(22.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

// ─── Stream diagram (camera ──●── monitor) ──────────────────────────────────

@Composable
private fun StreamDiagram(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .border(1.5.dp, Color.White.copy(alpha = 0.45f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Videocam, null, tint = Color.White.copy(0.75f), modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(5.dp))
        repeat(4) { i ->
            Box(Modifier.width(5.dp).height(2.dp).background(Color.White.copy(alpha = 0.3f)))
            if (i < 3) Spacer(Modifier.width(3.dp))
        }
        Spacer(Modifier.width(4.dp))
        Box(Modifier.size(9.dp).clip(CircleShape).background(Color(0xFFE53935)))
        Spacer(Modifier.width(4.dp))
        repeat(4) { i ->
            Box(Modifier.width(5.dp).height(2.dp).background(Color.White.copy(alpha = 0.3f)))
            if (i < 3) Spacer(Modifier.width(3.dp))
        }
        Spacer(Modifier.width(5.dp))
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .border(1.5.dp, Color.White.copy(alpha = 0.45f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.DesktopWindows, null, tint = Color.White.copy(0.75f), modifier = Modifier.size(16.dp))
        }
    }
}

// ─── Shared utility blocks ───────────────────────────────────────────────────

@Composable
internal fun ErrorBlock(msg: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Default.VideocamOff,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(12.dp))
        Text("Couldn't reach the NVR", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(msg, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry, shape = RoundedCornerShape(14.dp)) { Text("Retry") }
    }
}

@Composable
internal fun EmptyBlock(title: String, body: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Default.Videocam,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Text(title, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(body, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
