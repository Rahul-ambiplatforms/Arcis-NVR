package com.arcisai.nvr.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arcisai.nvr.viewmodel.NvrViewModel
import java.time.Instant
import java.time.ZoneOffset

private enum class DiskNav { Overview, ChannelSelect, Recordings }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiskScreen(vm: NvrViewModel, onBack: () -> Unit) {
    LaunchedEffect(Unit) { vm.loadDiskStat() }

    var nav       by remember { mutableStateOf(DiskNav.Overview) }
    var dlChannel by remember { mutableIntStateOf(0) }

    when (nav) {
        DiskNav.Overview -> StorageOverviewPage(vm, onBack) { nav = DiskNav.ChannelSelect }
        DiskNav.ChannelSelect -> ChannelSelectPage(
            channelCount = vm.maxChannels,
            getLabel     = { vm.channelDisplayName(it) },
            onSelect     = { ch -> dlChannel = ch; nav = DiskNav.Recordings },
            onBack       = { nav = DiskNav.Overview },
        )
        DiskNav.Recordings -> RecordingDownloadPage(vm, dlChannel) { nav = DiskNav.ChannelSelect }
    }
}

// ── Overview ──────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StorageOverviewPage(vm: NvrViewModel, onBack: () -> Unit, onDownload: () -> Unit) {
    val stat       = vm.diskStat
    // /netsdk/Stat returns HDDState.{UsedPercentage,Total,Used,Free} as strings,
    // e.g. UsedPercentage="2.5%", Total="3724.0 GB", Used="91.4 GB"
    val hddState   = stat?.optJSONObject("HDDState")
    val usedPct    = hddState?.optString("UsedPercentage", "")?.removeSuffix("%")?.toDoubleOrNull()
    val fraction: Float = when {
        hddState == null -> -1f
        usedPct != null && usedPct >= 0 -> (usedPct / 100.0).toFloat().coerceIn(0f, 1f)
        else -> {
            val total = hddState.optString("Total", "0").substringBefore(' ').toDoubleOrNull() ?: -1.0
            val used  = hddState.optString("Used",  "0").substringBefore(' ').toDoubleOrNull() ?: -1.0
            if (total > 0 && used >= 0) (used / total).toFloat().coerceIn(0f, 1f) else -1f
        }
    }
    val pctLabel   = if (fraction >= 0f) "%.1f%%".format(fraction * 100) else "–"
    val usedLabel  = hddState?.optString("Used", "").takeIf { !it.isNullOrBlank() }
    val totalLabel = hddState?.optString("Total", "").takeIf { !it.isNullOrBlank() }
    val condition  = when {
        fraction < 0f    -> if (stat == null) "Loading…" else "Status unavailable"
        fraction >= 0.9f -> "Storage nearly full"
        fraction >= 0.7f -> "Storage running low"
        else             -> "In good condition"
    }
    val condColor: Color = when {
        fraction >= 0.9f -> MaterialTheme.colorScheme.error
        fraction >= 0.7f -> MaterialTheme.colorScheme.tertiary
        else             -> Color(0xFF34C759)
    }

    var showSheet by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Storage Settings", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Spacer(Modifier.height(12.dp))
            Text(
                "Local Storage",
                fontSize   = 12.sp,
                fontWeight = FontWeight.Medium,
                color      = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier   = Modifier.padding(start = 20.dp, bottom = 6.dp),
            )
            ElevatedCard(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                shape    = RoundedCornerShape(12.dp),
            ) {
                // Row 1: storage space
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { if (stat != null) showSheet = true }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Local Storage Space",
                            fontWeight = FontWeight.Medium, fontSize = 15.sp)
                        Text(
                            when {
                                stat == null -> "Loading…"
                                usedLabel != null && totalLabel != null -> "$usedLabel used of $totalLabel ($pctLabel)"
                                else -> "Used Storage: $pctLabel"
                            },
                            fontSize = 12.sp,
                            color    = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (stat != null) {
                        Text(condition, fontSize = 12.sp, color = condColor,
                            fontWeight = FontWeight.Medium)
                        Spacer(Modifier.width(6.dp))
                    }
                    Icon(Icons.Default.ChevronRight, null,
                        tint     = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp))
                }
                HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
                // Row 2: download
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onDownload)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Download Local Video Files",
                        fontWeight = FontWeight.Medium,
                        fontSize   = 15.sp,
                        modifier   = Modifier.weight(1f))
                    Icon(Icons.Default.ChevronRight, null,
                        tint     = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp))
                }
            }
        }
    }

    if (showSheet) {
        ModalBottomSheet(onDismissRequest = { showSheet = false }) {
            StorageDetailSheet(fraction, pctLabel, condition, condColor, usedLabel, totalLabel)
            Spacer(Modifier.height(36.dp))
        }
    }
}

@Composable
private fun StorageDetailSheet(
    fraction: Float,
    pctLabel: String,
    condition: String,
    condColor: Color,
    usedLabel: String? = null,
    totalLabel: String? = null,
) {
    val primary = MaterialTheme.colorScheme.primary
    val track   = MaterialTheme.colorScheme.surfaceVariant

    Column(
        modifier            = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Local Storage", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
        Spacer(Modifier.height(24.dp))
        Row(
            modifier          = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Left: icon + label
            Row(
                modifier          = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Storage, null,
                    modifier = Modifier.size(40.dp),
                    tint     = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Storage Info", fontWeight = FontWeight.Medium)
                    if (usedLabel != null && totalLabel != null) {
                        Text("$usedLabel / $totalLabel",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("Used $pctLabel",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                }
            }
            // Right: donut ring
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(84.dp)) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val strokePx = 9.dp.toPx()
                    val inset    = strokePx / 2f
                    val arcSize  = androidx.compose.ui.geometry.Size(
                        size.width - strokePx, size.height - strokePx)
                    val arcOff   = androidx.compose.ui.geometry.Offset(inset, inset)
                    drawArc(track, -90f, 360f, false,
                        topLeft = arcOff, size = arcSize, style = Stroke(strokePx))
                    val sweep = if (fraction >= 0f) (fraction * 360f).coerceAtLeast(2f) else 0f
                    if (sweep > 0f) drawArc(primary, -90f, sweep, false,
                        topLeft = arcOff, size = arcSize,
                        style = Stroke(strokePx, cap = StrokeCap.Round))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(pctLabel, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text("Used", fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))
        Text(
            "When storage is full, older recordings are overwritten in a loop. " +
                "Important footage may not be recoverable.",
            fontSize  = 12.sp,
            color     = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(condition, fontSize = 13.sp, color = condColor, fontWeight = FontWeight.Medium)
    }
}

// ── Channel Select ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChannelSelectPage(
    channelCount: Int,
    getLabel: (Int) -> String,
    onSelect: (Int) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Select Channel", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            item {
                Text(
                    "Base Station Channel",
                    fontSize   = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color      = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier   = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 6.dp),
                )
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    shape    = RoundedCornerShape(12.dp),
                ) {
                    for (ch in 0 until channelCount) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(ch) }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(getLabel(ch),
                                modifier   = Modifier.weight(1f),
                                fontWeight = FontWeight.Medium,
                                fontSize   = 15.sp)
                            Icon(Icons.Default.ChevronRight, null,
                                tint     = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp))
                        }
                        if (ch < channelCount - 1) HorizontalDivider(Modifier.padding(start = 16.dp))
                    }
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

// ── Recording Download ─────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecordingDownloadPage(
    vm: NvrViewModel,
    channelId: Int,
    onBack: () -> Unit,
) {
    var dayMillis by remember { mutableLongStateOf(dlDayStart(System.currentTimeMillis())) }
    val dayLabel   = dlDayLabel(dayMillis)

    LaunchedEffect(channelId, dayMillis) { vm.searchRecordings(channelId, dayMillis) }

    val segments = vm.recordSegments.orEmpty()
    var selected by remember { mutableStateOf<Set<NvrViewModel.RecordSegment>>(emptySet()) }
    LaunchedEffect(channelId, dayMillis) { selected = emptySet() }

    val byHour: List<Pair<Int, List<NvrViewModel.RecordSegment>>> = remember(segments) {
        segments
            .groupBy { Instant.ofEpochSecond(it.startSec).atZone(ZoneOffset.UTC).hour }
            .entries.sortedBy { it.key }
            .map { it.key to it.value }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Download recordings", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { dayMillis -= 86_400_000L }) {
                        Icon(Icons.Default.ChevronLeft, "Previous day")
                    }
                    Text(dayLabel, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.align(Alignment.CenterVertically))
                    IconButton(onClick = { dayMillis += 86_400_000L }) {
                        Icon(Icons.Default.ChevronRight, "Next day")
                    }
                },
            )
        },
        bottomBar = {
            Column {
                val dlProgress = vm.downloadProgress
                val dlStatus   = vm.downloadStatus
                val batchLeft  = vm.batchRemaining
                if (dlProgress != null || dlStatus != null || batchLeft > 0) {
                    Surface(
                        color    = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    buildString {
                                        append(dlStatus ?: "Downloading…")
                                        if (batchLeft > 0) append(" ($batchLeft remaining)")
                                    },
                                    fontSize = 13.sp,
                                    modifier = Modifier.weight(1f),
                                    color    = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                                IconButton(
                                    onClick  = { vm.cancelBatchDownload() },
                                    modifier = Modifier.size(28.dp),
                                ) {
                                    Icon(Icons.Default.Close, "Cancel",
                                        modifier = Modifier.size(16.dp))
                                }
                            }
                            if (dlProgress != null) {
                                LinearProgressIndicator(
                                    progress = { dlProgress },
                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                )
                            }
                        }
                    }
                }
                Surface(tonalElevation = 3.dp) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment     = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (selected.isEmpty()) "Select clips to download"
                            else "${selected.size} clip${if (selected.size > 1) "s" else ""} selected",
                            fontSize = 13.sp,
                            color    = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            onClick = {
                                if (selected.isNotEmpty()) {
                                    vm.startBatchDownload(selected.sortedBy { it.startSec })
                                    selected = emptySet()
                                }
                            },
                            enabled = selected.isNotEmpty() && vm.downloadProgress == null,
                        ) { Text("Download") }
                    }
                }
            }
        },
    ) { pad ->
        when {
            vm.recordSearchBusy -> Box(
                Modifier.padding(pad).fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            vm.recordSearchStatus != null -> Box(
                Modifier.padding(pad).fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { Text(vm.recordSearchStatus!!, color = MaterialTheme.colorScheme.onSurfaceVariant) }

            byHour.isEmpty() -> Box(
                Modifier.padding(pad).fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { Text("No recordings for this day.", color = MaterialTheme.colorScheme.onSurfaceVariant) }

            else -> LazyColumn(
                modifier       = Modifier.padding(pad),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                byHour.forEach { (hour, segs) ->
                    item(key = "h$hour") {
                        Text(
                            "%02d:00".format(hour),
                            fontWeight = FontWeight.SemiBold,
                            fontSize   = 14.sp,
                            modifier   = Modifier.padding(
                                start = 16.dp, top = 14.dp, bottom = 8.dp),
                        )
                    }
                    val rows = segs.chunked(4)
                    items(rows) { row ->
                        Row(
                            modifier              = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            row.forEach { seg ->
                                RecordingChip(
                                    seg      = seg,
                                    checked  = seg in selected,
                                    onToggle = {
                                        selected = if (seg in selected) selected - seg
                                        else selected + seg
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordingChip(
    seg: NvrViewModel.RecordSegment,
    checked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val primary  = MaterialTheme.colorScheme.primary
    val surface  = MaterialTheme.colorScheme.surfaceVariant
    val outline  = MaterialTheme.colorScheme.outline
    val typeColor: Color = when (seg.type.trim()) {
        "1"  -> MaterialTheme.colorScheme.primary
        "2"  -> Color(0xFFFF9500)
        "4"  -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(
        modifier            = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (checked) primary.copy(alpha = 0.12f) else surface)
            .clickable(onClick = onToggle)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Default.Videocam, null,
            modifier = Modifier.size(22.dp), tint = typeColor)
        Spacer(Modifier.height(3.dp))
        Text(dlHM(seg.startSec), fontSize = 10.sp, fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        // Circular check indicator
        Box(
            modifier = Modifier
                .size(14.dp)
                .clip(CircleShape)
                .background(if (checked) primary else Color.Transparent)
                .border(1.5.dp, if (checked) primary else outline.copy(alpha = 0.5f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Icon(Icons.Default.Check, null,
                    modifier = Modifier.size(9.dp), tint = Color.White)
            }
        }
    }
}

// ── date helpers ───────────────────────────────────────────────────────────────

private fun dlDayStart(millis: Long): Long {
    val day = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
    return day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
}

private fun dlDayLabel(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString()
        .replace("-", "/")

private fun dlHM(sec: Long): String =
    Instant.ofEpochSecond(sec).atZone(ZoneOffset.UTC)
        .toLocalTime().let { "%02d:%02d".format(it.hour, it.minute) }
