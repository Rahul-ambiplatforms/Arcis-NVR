package com.arcisai.nvr.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arcisai.nvr.viewmodel.NvrViewModel
import org.json.JSONObject

// ── Verified firmware payload at /netsdk/General/Time: ────────────────────────
//   { TimeZone, DateFMT, TimeFMT, PersianCalendar,
//     NTP: { NtpServer, IsSyncTime }, SummerTime: { SummerTimeUse } }

private enum class TimeNav { Overview, TimeZone, DST }

private data class TzEntry(val offset: String, val city: String)

private val TZ_LIST = listOf(
    TzEntry("-12:00", "International Date Line West"),
    TzEntry("-11:00", "Midway/Samoa"),
    TzEntry("-10:00", "Honolulu/USA"),
    TzEntry("-09:00", "Anchorage/USA"),
    TzEntry("-08:00", "Los Angeles/USA"),
    TzEntry("-08:00", "Vancouver/Canada"),
    TzEntry("-07:00", "Denver/USA"),
    TzEntry("-07:00", "Phoenix/USA"),
    TzEntry("-06:00", "Chicago/USA"),
    TzEntry("-06:00", "Mexico City/Mexico"),
    TzEntry("-05:00", "New York/USA"),
    TzEntry("-05:00", "Toronto/Canada"),
    TzEntry("-05:00", "Bogota/Colombia"),
    TzEntry("-04:00", "Santiago/Chile"),
    TzEntry("-04:00", "Halifax/Canada"),
    TzEntry("-03:30", "St. John's/Canada"),
    TzEntry("-03:00", "São Paulo/Brazil"),
    TzEntry("-03:00", "Buenos Aires/Argentina"),
    TzEntry("-02:00", "Mid-Atlantic"),
    TzEntry("-01:00", "Azores/Portugal"),
    TzEntry("+00:00", "London/UK"),
    TzEntry("+00:00", "Dublin/Ireland"),
    TzEntry("+00:00", "Lisbon/Portugal"),
    TzEntry("+00:00", "Accra/Ghana"),
    TzEntry("+01:00", "Paris/France"),
    TzEntry("+01:00", "Berlin/Germany"),
    TzEntry("+01:00", "Rome/Italy"),
    TzEntry("+01:00", "Madrid/Spain"),
    TzEntry("+01:00", "Lagos/Nigeria"),
    TzEntry("+02:00", "Athens/Greece"),
    TzEntry("+02:00", "Cairo/Egypt"),
    TzEntry("+02:00", "Johannesburg/South Africa"),
    TzEntry("+02:00", "Helsinki/Finland"),
    TzEntry("+02:00", "Istanbul/Turkey"),
    TzEntry("+03:00", "Moscow/Russia"),
    TzEntry("+03:00", "Nairobi/Kenya"),
    TzEntry("+03:00", "Riyadh/Saudi Arabia"),
    TzEntry("+03:00", "Baghdad/Iraq"),
    TzEntry("+03:30", "Tehran/Iran"),
    TzEntry("+04:00", "Dubai/UAE"),
    TzEntry("+04:00", "Baku/Azerbaijan"),
    TzEntry("+04:30", "Kabul/Afghanistan"),
    TzEntry("+05:00", "Karachi/Pakistan"),
    TzEntry("+05:00", "Tashkent/Uzbekistan"),
    TzEntry("+05:30", "Mumbai/India"),
    TzEntry("+05:30", "New Delhi/India"),
    TzEntry("+05:30", "Kolkata/India"),
    TzEntry("+05:45", "Kathmandu/Nepal"),
    TzEntry("+06:00", "Dhaka/Bangladesh"),
    TzEntry("+06:00", "Almaty/Kazakhstan"),
    TzEntry("+06:30", "Yangon/Myanmar"),
    TzEntry("+07:00", "Bangkok/Thailand"),
    TzEntry("+07:00", "Jakarta/Indonesia"),
    TzEntry("+07:00", "Ho Chi Minh/Vietnam"),
    TzEntry("+07:00", "Hanoi/Vietnam"),
    TzEntry("+08:00", "Beijing/China"),
    TzEntry("+08:00", "Shanghai/China"),
    TzEntry("+08:00", "Hong Kong/China"),
    TzEntry("+08:00", "Taipei/Taiwan"),
    TzEntry("+08:00", "Singapore/Singapore"),
    TzEntry("+08:00", "Kuala Lumpur/Malaysia"),
    TzEntry("+08:00", "Manila/Philippines"),
    TzEntry("+08:00", "Perth/Australia"),
    TzEntry("+09:00", "Seoul/South Korea"),
    TzEntry("+09:00", "Tokyo/Japan"),
    TzEntry("+09:30", "Darwin/Australia"),
    TzEntry("+09:30", "Adelaide/Australia"),
    TzEntry("+10:00", "Sydney/Australia"),
    TzEntry("+10:00", "Melbourne/Australia"),
    TzEntry("+10:00", "Brisbane/Australia"),
    TzEntry("+11:00", "Noumea/New Caledonia"),
    TzEntry("+12:00", "Auckland/New Zealand"),
    TzEntry("+12:00", "Fiji/Fiji"),
)

private fun formatGmt(offset: String): String {
    if (offset.isBlank()) return ""
    val sign = if (offset.startsWith("-")) "-" else "+"
    val body = offset.trimStart('+', '-')
    val parts = body.split(":")
    val h = parts.getOrNull(0)?.toIntOrNull() ?: 0
    val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
    return "GMT $sign$h:${"%02d".format(m)}"
}

// ── Root ──────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeScreen(vm: NvrViewModel, onBack: () -> Unit) {
    var nav by remember { mutableStateOf(TimeNav.Overview) }

    when (nav) {
        TimeNav.Overview -> TimeOverviewPage(
            vm       = vm,
            onBack   = onBack,
            onTz     = { nav = TimeNav.TimeZone },
            onDst    = { nav = TimeNav.DST },
        )
        TimeNav.TimeZone -> TimeZonePage(
            vm     = vm,
            onBack = { nav = TimeNav.Overview },
        )
        TimeNav.DST -> DstPage(
            vm     = vm,
            onBack = { nav = TimeNav.Overview },
        )
    }
}

// ── Overview ──────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeOverviewPage(
    vm: NvrViewModel,
    onBack: () -> Unit,
    onTz: () -> Unit,
    onDst: () -> Unit,
) {
    val currentTz = vm.generalTimeCfg?.optString("TimeZone", "")
    var showSyncDialog by remember { mutableStateOf(false) }
    var syncResult    by remember { mutableStateOf<String?>(null) }
    var syncing       by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Time Settings", fontWeight = FontWeight.SemiBold) },
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
                "Time Settings",
                fontSize   = 12.sp,
                fontWeight = FontWeight.Medium,
                color      = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier   = Modifier.padding(start = 20.dp, bottom = 6.dp),
            )
            ElevatedCard(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                shape    = RoundedCornerShape(12.dp),
            ) {
                // Time Sync
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showSyncDialog = true }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Time Sync", fontWeight = FontWeight.Medium, fontSize = 15.sp)
                        Text("Sync Device Time with Phone",
                            fontSize = 12.sp,
                            color    = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Default.ChevronRight, null,
                        tint     = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp))
                }
                HorizontalDivider(Modifier.padding(start = 16.dp))
                // Time Zone
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onTz)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Time Zone Settings",
                        fontWeight = FontWeight.Medium,
                        fontSize   = 15.sp,
                        modifier   = Modifier.weight(1f))
                    if (!currentTz.isNullOrBlank()) {
                        Text(formatGmt(currentTz),
                            fontSize = 13.sp,
                            color    = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(4.dp))
                    }
                    Icon(Icons.Default.ChevronRight, null,
                        tint     = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp))
                }
                HorizontalDivider(Modifier.padding(start = 16.dp))
                // DST
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onDst)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Daylight Saving Time",
                        fontWeight = FontWeight.Medium,
                        fontSize   = 15.sp,
                        modifier   = Modifier.weight(1f))
                    Icon(Icons.Default.ChevronRight, null,
                        tint     = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp))
                }
            }

            // Result toast
            syncResult?.let { msg ->
                Spacer(Modifier.height(16.dp))
                Text(msg,
                    modifier = Modifier.padding(horizontal = 20.dp),
                    fontSize = 13.sp,
                    color    = MaterialTheme.colorScheme.onSurfaceVariant)
                LaunchedEffect(msg) {
                    kotlinx.coroutines.delay(3_000)
                    syncResult = null
                }
            }
        }
    }

    if (showSyncDialog) {
        AlertDialog(
            onDismissRequest = { if (!syncing) showSyncDialog = false },
            title = { Text("Time Sync") },
            text  = { Text("The device time will be synced with your phone. Continue?") },
            confirmButton = {
                Button(
                    onClick = {
                        syncing = true
                        vm.syncTimeWithPhone { ok, msg ->
                            syncing = false
                            showSyncDialog = false
                            syncResult = msg
                        }
                    },
                    enabled = !syncing,
                ) {
                    if (syncing) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Confirm")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showSyncDialog = false }, enabled = !syncing) {
                    Text("Cancel")
                }
            },
        )
    }
}

// ── Time Zone ─────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeZonePage(vm: NvrViewModel, onBack: () -> Unit) {
    LaunchedEffect(Unit) { vm.loadGeneralTime() }

    val cfg    = vm.generalTimeCfg
    var query  by remember { mutableStateOf("") }
    var picked by remember(cfg) {
        mutableStateOf(cfg?.optString("TimeZone", "+05:30") ?: "+05:30")
    }
    val snack  = rememberSettingsSnackbar(vm.settingStatus)

    val filtered = remember(query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) TZ_LIST
        else TZ_LIST.filter { it.city.lowercase().contains(q) || it.offset.contains(q) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Time Zone Settings", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            if (cfg != null) {
                                cfg.put("TimeZone", picked)
                                vm.saveGeneralTime(cfg)
                                onBack()
                            }
                        },
                        enabled = cfg != null,
                    ) { Text("Confirm") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (cfg == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                return@Column
            }

            OutlinedTextField(
                value            = query,
                onValueChange    = { query = it },
                placeholder      = { Text("Search Region or Time Zone") },
                leadingIcon      = { Icon(Icons.Default.Search, null) },
                singleLine       = true,
                modifier         = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                keyboardOptions  = KeyboardOptions(imeAction = ImeAction.Search),
                shape            = RoundedCornerShape(12.dp),
            )

            Text(
                "Select time zone",
                fontSize   = 12.sp,
                color      = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier   = Modifier.padding(start = 20.dp, top = 4.dp, bottom = 4.dp),
            )

            LazyColumn {
                items(filtered, key = { "${it.city}${it.offset}" }) { tz ->
                    val selected = tz.offset == picked
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { picked = tz.offset }
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            tz.city,
                            modifier   = Modifier.weight(1f),
                            fontSize   = 15.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            formatGmt(tz.offset),
                            fontSize = 14.sp,
                            color    = if (selected) MaterialTheme.colorScheme.primary
                                       else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider(Modifier.padding(start = 20.dp))
                }
            }
        }
    }
}

// ── DST ───────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DstPage(vm: NvrViewModel, onBack: () -> Unit) {
    LaunchedEffect(Unit) { vm.loadGeneralTime() }

    val cfg    = vm.generalTimeCfg
    val summer = cfg?.optJSONObject("SummerTime")
    var dst    by remember(summer) {
        mutableStateOf(nvrBool(summer?.optString("SummerTimeUse", "False") ?: "False"))
    }
    val snack  = rememberSettingsSnackbar(vm.settingStatus)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Daylight Saving Time", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            if (cfg != null) {
                                val s = summer ?: JSONObject().also { cfg.put("SummerTime", it) }
                                s.put("SummerTimeUse", nvrStrBool(dst))
                                vm.saveGeneralTime(cfg)
                                onBack()
                            }
                        },
                        enabled = cfg != null,
                    ) { Text("Confirm") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (cfg == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                return@Column
            }

            Spacer(Modifier.height(12.dp))
            Text(
                "Daylight Saving Time",
                fontSize   = 12.sp,
                fontWeight = FontWeight.Medium,
                color      = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier   = Modifier.padding(start = 20.dp, bottom = 6.dp),
            )
            ElevatedCard(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                shape    = RoundedCornerShape(12.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Daylight Saving Time",
                        fontWeight = FontWeight.Medium,
                        fontSize   = 15.sp,
                        modifier   = Modifier.weight(1f))
                    Switch(checked = dst, onCheckedChange = { dst = it })
                }
            }
        }
    }
}
