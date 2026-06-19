package com.arcisai.nvr.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsHubScreen(
    vm: NvrViewModel,
    onPick: (String) -> Unit,
    onLogout: () -> Unit,
    onBack: (() -> Unit)? = null,
    onSwitchNvr: (() -> Unit)? = null,
    currentNvrName: String? = null,
    accountEmail: String? = null,
    onChannelSettings: (Int) -> Unit = {},
    onAboutDevice: () -> Unit = {},
) {
    LaunchedEffect(Unit) {
        vm.loadOrdinary()
        vm.loadNetworkCfg()
        vm.loadDiskStat()
    }

    var showRebootConfirm by remember { mutableStateOf(false) }
    var showVolumeDialog  by remember { mutableStateOf(false) }
    var showRenameDialog  by remember { mutableStateOf(false) }
    var alertVolume       by remember { mutableStateOf(80) }
    var alarmSyncEnabled  by remember { mutableStateOf(false) }
    var renameText        by remember(vm.displayNvrName) { mutableStateOf(vm.displayNvrName) }

    val clipboard = LocalClipboardManager.current

    val capacityPct: String? = remember(vm.diskStat) {
        vm.diskStat?.optJSONObject("HDDState")?.let { hdd ->
            val pct = hdd.optString("UsedPercentage", "").removeSuffix("%").toDoubleOrNull()
            if (pct != null && pct >= 0) return@remember "%.1f%%".format(pct)
            val total = hdd.optString("Total", "0").substringBefore(' ').toDoubleOrNull() ?: -1.0
            val used  = hdd.optString("Used",  "0").substringBefore(' ').toDoubleOrNull() ?: -1.0
            if (total > 0 && used >= 0) "%.1f%%".format(used / total * 100.0) else null
        }
    }

    val bg      = MaterialTheme.colorScheme.background
    val surface = MaterialTheme.colorScheme.surface

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.SemiBold, fontSize = 17.sp) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                    }
                },
                actions = {
                    if (onSwitchNvr != null) {
                        IconButton(onClick = onSwitchNvr) {
                            Icon(Icons.Default.SwapHoriz, "Switch NVR")
                        }
                    }
                    IconButton(onClick = onLogout) {
                        Icon(Icons.AutoMirrored.Filled.Logout, "Logout")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = bg),
            )
        },
        containerColor = bg,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(bottom = 40.dp),
        ) {

            // ── Device card ────────────────────────────────────────────────
            item {
                DeviceCard(
                    displayName  = vm.displayNvrName,
                    info         = vm.deviceInfo,
                    online       = vm.nvrOnline,
                    capacityPct  = capacityPct,
                    onEditName   = { showRenameDialog = true },
                    onCopyId     = { id -> clipboard.setText(AnnotatedString(id)) },
                )
            }

            // ── Siren ──────────────────────────────────────────────────────
            item { HubSectionHeader("Siren") }
            item {
                SettingsGroup {
                    SwitchRow(
                        title    = "Base Station Alarm Sync",
                        subtitle = "When the device triggers an alarm, the base station will also sound an alarm.",
                        checked  = alarmSyncEnabled,
                        onChange = { alarmSyncEnabled = it },
                    )
                    GroupDivider()
                    ValueNavRow(
                        title   = "Alert Volume",
                        value   = alertVolume.toString(),
                        onClick = { showVolumeDialog = true },
                    )
                }
            }

            // ── Base Station Settings ──────────────────────────────────────
            item { HubSectionHeader("Base Station Settings") }
            item {
                SettingsGroup {
                    NavRow("Storage Settings", null, Icons.Default.Storage)  { onPick("disk") }
                    GroupDivider()
                    NavRow("Time Settings",    null, Icons.Default.Schedule) { onPick("time") }
                    GroupDivider()
                    NavRow("Password Settings",
                        "Change your device login password",
                        Icons.Default.Lock) { onPick("password") }
                }
            }

            // ── Notifications ──────────────────────────────────────────────
            item { HubSectionHeader("Notifications") }
            item {
                SettingsGroup {
                    NavRow("Email Alerts",
                        "Receive motion and alarm events by email",
                        Icons.Default.Email) { onPick("smtp") }
                }
            }

            // ── Device ─────────────────────────────────────────────────────
            item { HubSectionHeader("Device") }
            item {
                SettingsGroup {
                    NavRow("Device Name",
                        "Rename this NVR",
                        Icons.Default.Edit) { onPick("general") }
                }
            }

            // ── About ──────────────────────────────────────────────────────
            item { HubSectionHeader("About") }
            item {
                SettingsGroup {
                    NavRow("About Device", null, Icons.Default.Info) { onAboutDevice() }
                }
            }

            // ── Connected Channels ─────────────────────────────────────────
            val allChs = vm.channels
            if (allChs.isNotEmpty()) {
                item { HubSectionHeader("Connected Devices") }
                item {
                    SettingsGroup {
                        allChs.forEachIndexed { idx, ch ->
                            val online   = vm.connectedChannels?.contains(ch.id)
                            val hasCam   = ch.ipAddr.isNotBlank()
                            val canOpen  = hasCam && online != false
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = canOpen) { onChannelSettings(ch.id) }
                                    .padding(horizontal = 16.dp, vertical = 14.dp)
                                    .then(if (!canOpen) Modifier.alpha(0.45f) else Modifier),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    vm.channelDisplayName(ch.id),
                                    modifier = Modifier.weight(1f),
                                    fontWeight = FontWeight.Normal,
                                    fontSize = 15.sp,
                                )
                                if (!hasCam) {
                                    Text(
                                        "No camera",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(end = 6.dp),
                                    )
                                } else if (online != null) {
                                    Text(
                                        if (online) "Online" else "Offline",
                                        fontSize = 14.sp,
                                        color = if (online) ArcisGreen else MaterialTheme.colorScheme.error,
                                        modifier = Modifier.padding(end = 6.dp),
                                    )
                                }
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowForwardIos,
                                    contentDescription = null,
                                    tint = if (canOpen) MaterialTheme.colorScheme.outline
                                           else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                                    modifier = Modifier.size(13.dp),
                                )
                            }
                            if (idx < allChs.lastIndex) GroupDivider()
                        }
                    }
                }
            }

            // ── Restart Device ─────────────────────────────────────────────
            item { Spacer(Modifier.height(20.dp)) }
            item {
                Button(
                    onClick = { showRebootConfirm = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .height(50.dp),
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                    ),
                ) {
                    Text("Restart Device", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                }
            }

            // ── Switch NVR / account ───────────────────────────────────────
            if (onSwitchNvr != null && !accountEmail.isNullOrBlank()) {
                item { Spacer(Modifier.height(8.dp)) }
                item {
                    SettingsGroup {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSwitchNvr() }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.SwapHoriz, null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(10.dp))
                            Text("Switch NVR",
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 15.sp)
                        }
                    }
                }
            }
        }
    }

    // ── Alert Volume dialog ────────────────────────────────────────────────────
    if (showVolumeDialog) {
        VolumeDialog(
            volume    = alertVolume,
            onConfirm = { v -> alertVolume = v; showVolumeDialog = false },
            onDismiss = { showVolumeDialog = false },
        )
    }

    // ── Rename dialog ──────────────────────────────────────────────────────────
    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title  = { Text("Rename Device") },
            text   = {
                OutlinedTextField(
                    value         = renameText,
                    onValueChange = { renameText = it },
                    singleLine    = true,
                    label         = { Text("Device name") },
                    modifier      = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (renameText.isNotBlank()) vm.setDisplayNvrName(renameText.trim())
                    showRenameDialog = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text("Cancel") }
            },
        )
    }

    // ── Reboot confirm dialog ──────────────────────────────────────────────────
    if (showRebootConfirm) {
        AlertDialog(
            onDismissRequest = { showRebootConfirm = false },
            title = { Text("Restart Device?") },
            text  = { Text("The NVR will restart. Live streams and recordings will be interrupted briefly.") },
            confirmButton = {
                TextButton(onClick = {
                    showRebootConfirm = false
                    onPick("maint")
                }) { Text("Go to Maintenance", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showRebootConfirm = false }) { Text("Cancel") }
            },
        )
    }
}

// ─── Device info card ─────────────────────────────────────────────────────────

@Composable
private fun DeviceCard(
    displayName: String,
    info: JSONObject?,
    online: Boolean,
    capacityPct: String?,
    onEditName: () -> Unit,
    onCopyId: (String) -> Unit,
) {
    val labelColor   = MaterialTheme.colorScheme.onSurfaceVariant
    val dividerColor = MaterialTheme.colorScheme.outlineVariant

    Card(
        modifier  = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        shape     = RoundedCornerShape(14.dp),
        colors    = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(0.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Router, null,
                        tint     = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(38.dp))
                }

                Spacer(Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(displayName, fontWeight = FontWeight.Bold, fontSize = 16.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false))
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Default.Edit, "Edit name",
                            tint     = labelColor,
                            modifier = Modifier.size(16.dp).clickable(onClick = onEditName))
                    }

                    if (capacityPct != null) {
                        Spacer(Modifier.height(2.dp))
                        Text("Capacity: Used $capacityPct", fontSize = 12.sp, color = labelColor)
                    }

                    if (info != null) {
                        val id = info.optString("UID").ifBlank { info.optString("HWID") }.ifBlank { "" }
                        if (id.isNotBlank()) {
                            Spacer(Modifier.height(2.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("ID: $id", fontSize = 12.sp, color = labelColor,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false))
                                Spacer(Modifier.width(4.dp))
                                Icon(Icons.Default.ContentCopy, "Copy ID", tint = labelColor,
                                    modifier = Modifier.size(14.dp).clickable { onCopyId(id) })
                            }
                        }
                        val model = info.optString("DeviceModel").ifBlank { "" }
                        if (model.isNotBlank()) Text("Model: $model", fontSize = 12.sp, color = labelColor)
                        val fw = info.optString("FWVersion").ifBlank { "" }
                        if (fw.isNotBlank()) Text("Firmware: $fw", fontSize = 12.sp, color = labelColor)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(thickness = 0.5.dp, color = dividerColor)
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Status", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(
                    if (online) "Online" else "Offline",
                    fontSize = 15.sp,
                    color    = if (online) ArcisGreen else MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

// ─── Section header ───────────────────────────────────────────────────────────

@Composable
private fun HubSectionHeader(text: String) {
    Text(
        text,
        modifier = Modifier.fillMaxWidth()
            .padding(start = 20.dp, end = 16.dp, top = 22.dp, bottom = 7.dp),
        fontSize   = 13.sp,
        fontWeight = FontWeight.Normal,
        color      = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// ─── Card group wrapper ───────────────────────────────────────────────────────

@Composable
private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier  = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape     = RoundedCornerShape(12.dp),
        colors    = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(0.dp),
    ) {
        Column(content = content)
    }
}

@Composable
private fun GroupDivider() {
    HorizontalDivider(
        modifier  = Modifier.padding(start = 16.dp),
        thickness = 0.5.dp,
        color     = MaterialTheme.colorScheme.outlineVariant,
    )
}

// ─── Row types ────────────────────────────────────────────────────────────────

@Composable
private fun NavRow(
    title: String,
    subtitle: String?,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    val labelColor   = MaterialTheme.colorScheme.onSurfaceVariant
    val chevronColor = MaterialTheme.colorScheme.outline

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = if (subtitle != null) 12.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null,
                    tint     = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Normal)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, fontSize = 12.sp, color = labelColor, lineHeight = 16.sp)
            }
        }
        Spacer(Modifier.width(6.dp))
        Icon(Icons.AutoMirrored.Filled.ArrowForwardIos, null,
            tint     = chevronColor,
            modifier = Modifier.size(13.dp))
    }
}

@Composable
private fun ValueNavRow(title: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text(value, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(4.dp))
        Icon(Icons.AutoMirrored.Filled.ArrowForwardIos, null,
            tint     = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(13.dp))
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp)
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked        = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = ArcisGreen,
            ),
        )
    }
}

// ─── Alert Volume bottom-sheet ────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VolumeDialog(
    volume: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var current by remember(volume) { mutableStateOf(volume.toFloat()) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor   = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Device Alarm Volume",
                fontWeight = FontWeight.SemiBold, fontSize = 17.sp,
                modifier = Modifier.padding(bottom = 24.dp))

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.VolumeDown, null,
                    tint     = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp))
                Slider(
                    value         = current,
                    onValueChange = { current = it },
                    valueRange    = 0f..100f,
                    modifier      = Modifier.weight(1f).padding(horizontal = 8.dp),
                    colors = SliderDefaults.colors(
                        thumbColor       = MaterialTheme.colorScheme.surface,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                    ),
                )
                Text(current.toInt().toString(), fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(32.dp))
            }

            Spacer(Modifier.height(24.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick  = onDismiss,
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape    = RoundedCornerShape(50),
                ) { Text("Cancel", fontSize = 16.sp) }
                Button(
                    onClick  = { onConfirm(current.toInt()) },
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape    = RoundedCornerShape(50),
                    colors   = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                    ),
                ) { Text("Confirm", fontSize = 16.sp) }
            }
        }
    }
}
