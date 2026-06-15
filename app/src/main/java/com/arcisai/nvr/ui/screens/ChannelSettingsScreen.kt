package com.arcisai.nvr.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
) {
    val ch       = vm.channels.getOrNull(channelId)
    val isOnline = vm.connectedChannels?.contains(channelId) == true
    val clipboard = LocalClipboardManager.current

    var displayName  by remember(channelId) { mutableStateOf(vm.channelDisplayName(channelId)) }
    var renameDraft  by remember { mutableStateOf("") }
    var showRename   by remember { mutableStateOf(false) }
    var showDelete   by remember { mutableStateOf(false) }

    val bg      = MaterialTheme.colorScheme.background
    val surface = MaterialTheme.colorScheme.surface
    val label   = MaterialTheme.colorScheme.onSurfaceVariant
    val divClr  = MaterialTheme.colorScheme.outlineVariant
    val chevron = MaterialTheme.colorScheme.outline

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
                        Box(
                            modifier = Modifier
                                .size(80.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Default.Videocam, null,
                                tint     = label.copy(alpha = 0.6f),
                                modifier = Modifier.size(38.dp),
                            )
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(displayName,
                                    fontWeight = FontWeight.Bold, fontSize = 16.sp,
                                    maxLines   = 1, overflow = TextOverflow.Ellipsis,
                                    modifier   = Modifier.weight(1f, fill = false))
                                Spacer(Modifier.width(6.dp))
                                Icon(Icons.Default.Edit, "Rename", tint = label,
                                    modifier = Modifier.size(16.dp).clickable {
                                        renameDraft = displayName; showRename = true
                                    })
                            }
                            if (ch != null && ch.modelName.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text("Model: ${ch.modelName}", fontSize = 13.sp, color = label)
                            }
                            if (ch != null && ch.ipAddr.isNotBlank()) {
                                Spacer(Modifier.height(2.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("IP: ${ch.ipAddr}", fontSize = 13.sp, color = label,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false))
                                    Spacer(Modifier.width(4.dp))
                                    Icon(Icons.Default.ContentCopy, "Copy IP", tint = label,
                                        modifier = Modifier.size(14.dp).clickable {
                                            clipboard.setText(AnnotatedString(ch.ipAddr))
                                        })
                                }
                            }
                        }
                    }
                }
            }

            // ── Base Station row ──────────────────────────────────────────
            item {
                ChGroup(surface) {
                    ChValueRow(
                        title   = "Base Station",
                        value   = if (isOnline) "Connected" else "Not Connected",
                        label   = label,
                        chevron = chevron,
                        onClick = {},
                    )
                }
                Spacer(Modifier.height(24.dp))
            }

            // ── Motion Detection and Notifications ────────────────────────
            item { ChSectionHeader("Motion Detection and Notifications") }
            item {
                ChGroup(surface) {
                    ChNavRow("Motion Detection Alerts",    label, chevron) {}
                    ChDividerRow(divClr)
                    ChNavRow("Push Notification Settings", label, chevron) {}
                }
            }

            // ── Device Settings ───────────────────────────────────────────
            item { ChSectionHeader("Device Settings") }
            item {
                ChGroup(surface) {
                    ChValueRow("Recording Settings", "Continuous Recording", label, chevron) { onNavigate("encode") }
                    ChDividerRow(divClr)
                    ChNavRow("Image & Sound Settings", label, chevron) { onNavigate("color") }
                    ChDividerRow(divClr)
                    ChNavRow("OSD",                   label, chevron) { onNavigate("osd") }
                    ChDividerRow(divClr)
                    ChNavRow("PTZ Control",            label, chevron) {}
                    ChDividerRow(divClr)
                    ChNavRow("Storage Settings",       label, chevron) { onNavigate("disk") }
                }
            }

            // ── Advanced Settings ─────────────────────────────────────────
            item { ChSectionHeader("Advanced Settings") }
            item {
                ChGroup(surface) {
                    ChNavRow("Advanced Settings", label, chevron) { onNavigate("general") }
                }
            }

            // ── About ─────────────────────────────────────────────────────
            item { ChSectionHeader("About") }
            item {
                ChGroup(surface) {
                    ChNavRow("About Device", label, chevron) { onNavigate("about-device") }
                    ChDividerRow(divClr)
                    ChNavRow("Share Device", label, chevron) {}
                }
            }

            // ── Delete Channel ────────────────────────────────────────────
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
                    Text("Delete Channel", fontSize = 16.sp, fontWeight = FontWeight.Medium)
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
                TextButton(onClick = { showDelete = false }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = false }) { Text("Cancel") }
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
            .padding(start = 20.dp, end = 16.dp, top = 22.dp, bottom = 7.dp),
        fontSize = 13.sp,
        color    = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ChNavRow(
    title: String,
    label: Color,
    chevron: Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Filled.ArrowForwardIos, null,
            tint = chevron, modifier = Modifier.size(13.dp))
    }
}

@Composable
private fun ChValueRow(
    title: String,
    value: String,
    label: Color,
    chevron: Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text(value, fontSize = 15.sp, color = label)
        Spacer(Modifier.width(4.dp))
        Icon(Icons.AutoMirrored.Filled.ArrowForwardIos, null,
            tint = chevron, modifier = Modifier.size(13.dp))
    }
}

@Composable
private fun ChDividerRow(divClr: Color) {
    HorizontalDivider(
        modifier  = Modifier.padding(start = 16.dp),
        thickness = 0.5.dp,
        color     = divClr,
    )
}
