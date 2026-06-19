package com.arcisai.nvr.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.*
import androidx.compose.material3.MenuAnchorType
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arcisai.nvr.viewmodel.NvrViewModel
import org.json.JSONObject

// ── IR-cut mode → user-friendly name + description ───────────────────────────
private fun ircutModeInfo(raw: String): Pair<String, String> = when (raw.lowercase()) {
    "auto"                -> "Smart Night Vision"      to "Automatically switches based on ambient light"
    "day", "daylight"     -> "Daytime Mode"            to "Always use colour mode, infrared off"
    "night"               -> "Infrared Night Vision"   to "Black & white infrared video at night"
    "ir"                  -> "Infrared Always On"      to "Always use infrared for B&W video"
    "light"               -> "Full-Color Night Vision" to "Colour night vision using the built-in spotlight"
    "smart"               -> "Smart Colour Vision"     to "Spotlight activates for colour when motion detected"
    "close"               -> "Night Vision Off"        to "Disable all night vision enhancements"
    else                  -> raw.replaceFirstChar { it.uppercase() } to ""
}


/**
 * OSD overlay settings + per-channel Day/Night IR-cut control.
 *
 * [channelId] != null, [osdOnly] = false → per-channel Night Vision (radio buttons)
 * [channelId] != null, [osdOnly] = true  → per-channel OSD overlay only (channel name)
 * [channelId] == null → global NVR view (all channel names + all IR-cut dropdowns)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OsdScreen(
    vm: NvrViewModel,
    onBack: () -> Unit,
    channelId: Int? = null,
    osdOnly: Boolean = false,
) {
    LaunchedEffect(Unit) { vm.loadOsd() }
    val snack  = rememberSettingsSnackbar(vm.settingStatus)
    val stream = vm.osdCfg

    val title = when {
        osdOnly         -> "OSD Overlay"
        channelId != null -> "Camera Vision Mode"
        else            -> "OSD & Day/Night"
    }

    // Extract current Ircut mode from the (possibly mutated) stream JSON and save
    // via the dedicated /netsdk/Stream/Ircut sub-path (PUT /netsdk/Stream fails silently).
    val saveChannelIrcut: () -> Unit = save@{
        if (channelId == null || stream == null) return@save
        val ircut = stream.optJSONArray("Ircut") ?: return@save
        val mode = (0 until ircut.length()).map { ircut.getJSONObject(it) }
            .firstOrNull { it.optInt("ID", -1) == channelId }
            ?.optString("IrcutModeCur") ?: return@save
        vm.saveIrcutMode(channelId, mode)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = MaterialTheme.colorScheme.onSurface)
                    }
                },
                actions = {
                    if (channelId != null && !osdOnly && stream != null) {
                        TextButton(onClick = saveChannelIrcut) {
                            Text("Confirm", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        snackbarHost     = { SnackbarHost(snack) },
        containerColor   = MaterialTheme.colorScheme.background,
    ) { padding ->
        when {
            stream == null -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            channelId != null && osdOnly -> PerChannelOsdOnlyContent(
                stream    = stream,
                channelId = channelId,
                onSave    = { vm.saveOsd(stream) },
                modifier  = Modifier.padding(padding),
            )

            channelId != null -> PerChannelOsdContent(
                stream    = stream,
                channelId = channelId,
                onSave    = saveChannelIrcut,
                modifier  = Modifier.padding(padding),
            )

            else -> GlobalOsdContent(
                stream   = stream,
                onSave   = { vm.saveOsd(stream) },
                modifier = Modifier.padding(padding),
            )
        }
    }
}

// ─── Per-channel view (white redesign) ───────────────────────────────────────

@Composable
private fun PerChannelOsdContent(
    stream: JSONObject,
    channelId: Int,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ircut       = stream.optJSONArray("Ircut")
    val ircutEntry  = ircut?.let { arr ->
        (0 until arr.length()).map { arr.getJSONObject(it) }
            .firstOrNull { it.optInt("ID", -1) == channelId }
    }
    val ircutOptions = ircutEntry?.optJSONArray("IrcutModeRange")?.let { arr ->
        (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
    } ?: emptyList()
    var ircutMode by remember(channelId) {
        mutableStateOf(ircutEntry?.optString("IrcutModeCur", "") ?: "")
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {

        // ── Day/Night preview image ──────────────────────────────────────────
        DayNightPreviewImage(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp),
        )

        Spacer(Modifier.height(20.dp))

        // ── Night vision mode radio list ─────────────────────────────────────
        Text(
            "Night Vision Mode",
            modifier = Modifier.padding(start = 20.dp, bottom = 8.dp),
            fontSize  = 12.sp,
            fontWeight = FontWeight.Medium,
            color     = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Surface(
            modifier  = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            shape     = RoundedCornerShape(12.dp),
            color     = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            shadowElevation = 1.dp,
        ) {
            Column {
                if (ircutOptions.isEmpty()) {
                    Box(Modifier.padding(20.dp)) {
                        Text(
                            "This camera does not expose IR-cut control.",
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    ircutOptions.forEachIndexed { index, mode ->
                        val (name, desc) = ircutModeInfo(mode)
                        NightModeOptionRow(
                            title       = name,
                            description = desc,
                            selected    = ircutMode.equals(mode, ignoreCase = true),
                            primary     = MaterialTheme.colorScheme.primary,
                            onClick     = {
                                ircutMode = mode
                                ircutEntry?.put("IrcutModeCur", mode)
                            },
                        )
                        if (index < ircutOptions.size - 1) {
                            HorizontalDivider(
                                modifier  = Modifier.padding(start = 56.dp),
                                thickness = 0.5.dp,
                                color     = MaterialTheme.colorScheme.outlineVariant,
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(28.dp))

        Button(
            onClick  = onSave,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .height(50.dp),
            shape  = RoundedCornerShape(12.dp),
        ) { Text("Save Changes", fontSize = 15.sp) }

        Spacer(Modifier.height(40.dp))
    }
}

// ─── Per-channel OSD-only view (channel display name / video overlay) ─────────

@Composable
private fun PerChannelOsdOnlyContent(
    stream: JSONObject,
    channelId: Int,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val titles   = stream.optJSONArray("Title")
    val titleObj = titles?.let { arr ->
        (0 until arr.length()).map { arr.getJSONObject(it) }
            .firstOrNull { it.optInt("ID", -1) == channelId }
    }
    var channelName by remember(channelId) {
        mutableStateOf(titleObj?.optString("Text", "") ?: "")
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(20.dp))
        Text(
            "Video Overlay",
            modifier   = Modifier.padding(start = 20.dp, bottom = 8.dp),
            fontSize   = 12.sp,
            fontWeight = FontWeight.Medium,
            color      = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(
            modifier        = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            shape           = RoundedCornerShape(12.dp),
            color           = MaterialTheme.colorScheme.surface,
            tonalElevation  = 0.dp,
            shadowElevation = 1.dp,
        ) {
            if (titleObj == null) {
                Box(Modifier.padding(20.dp)) {
                    Text(
                        "No OSD title entry found for this channel.",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Channel Display Name",
                            fontSize   = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color      = MaterialTheme.colorScheme.onSurface)
                        Spacer(Modifier.height(2.dp))
                        Text("Overlay text on the live video",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(12.dp))
                    OutlinedTextField(
                        value         = channelName,
                        onValueChange = { v -> channelName = v; titleObj.put("Text", v) },
                        singleLine    = true,
                        modifier      = Modifier.width(120.dp),
                        shape         = RoundedCornerShape(8.dp),
                        colors        = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor   = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                            focusedTextColor     = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor   = MaterialTheme.colorScheme.onSurface,
                        ),
                    )
                }
            }
        }

        Spacer(Modifier.height(28.dp))

        Button(
            onClick  = onSave,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .height(50.dp),
            shape  = RoundedCornerShape(12.dp),
        ) { Text("Save", fontSize = 15.sp) }

        Spacer(Modifier.height(40.dp))
    }
}

// ─── Day/Night preview image ──────────────────────────────────────────────────

@Composable
private fun DayNightPreviewImage(modifier: Modifier = Modifier) {
    Box(modifier = modifier) {
        Row(modifier = Modifier.fillMaxSize()) {
            // ── Day half ──────────────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xFF4FC3F7), Color(0xFF81C784), Color(0xFF388E3C)),
                        )
                    ),
            ) {
                DayScene(modifier = Modifier.fillMaxSize())
                // Badge
                Surface(
                    modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
                    shape    = RoundedCornerShape(20.dp),
                    color    = Color.Black.copy(alpha = 0.28f),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.WbSunny, null,
                            tint = Color(0xFFFDD835), modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Daytime", color = Color.White, fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            // ── Night half ────────────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xFF090916), Color(0xFF1A1A45), Color(0xFF0D1B2A)),
                        )
                    ),
            ) {
                NightScene(modifier = Modifier.fillMaxSize())
                // Badge
                Surface(
                    modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp),
                    shape    = RoundedCornerShape(20.dp),
                    color    = Color.Black.copy(alpha = 0.28f),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.NightsStay, null,
                            tint = Color(0xFFB3E5FC), modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Night", color = Color.White, fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        // Centre divider
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .width(2.dp)
                .fillMaxHeight()
                .background(Color.White.copy(alpha = 0.85f)),
        )
    }
}

@Composable
private fun DayScene(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        // Sun
        drawCircle(color = Color(0xFFFDD835), radius = h * 0.09f, center = Offset(w * 0.75f, h * 0.18f))

        // Ground
        drawRect(Color(0xFF4CAF50), topLeft = Offset(0f, h * 0.72f), size = Size(w, h * 0.28f))

        val wallC   = Color(0xFFF5F5F5)
        val roofC   = Color(0xFF8D6E63)
        val winC    = Color(0xFF64B5F6)
        val doorC   = Color(0xFF6D4C41)
        val bLeft   = w * 0.08f; val bRight = w * 0.92f
        val bBottom = h * 0.72f; val bTop   = h * 0.44f

        // Walls
        drawRect(wallC, topLeft = Offset(bLeft, bTop), size = Size(bRight - bLeft, bBottom - bTop))
        // Roof
        val roof = Path().apply {
            moveTo(w * 0.03f, bTop); lineTo(w * 0.5f, h * 0.2f); lineTo(w * 0.97f, bTop); close()
        }
        drawPath(roof, roofC)
        // Door
        val dW = w * 0.17f; val dH = h * 0.2f
        drawRect(doorC, topLeft = Offset(w * 0.5f - dW / 2, bBottom - dH), size = Size(dW, dH))
        // Windows
        val wW = w * 0.17f; val wH = h * 0.12f; val wTop = bTop + h * 0.08f
        drawRect(winC, topLeft = Offset(w * 0.16f, wTop), size = Size(wW, wH))
        drawRect(winC, topLeft = Offset(w * 0.67f, wTop), size = Size(wW, wH))
    }
}

@Composable
private fun NightScene(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        // Stars
        listOf(
            Offset(w * 0.15f, h * 0.1f),  Offset(w * 0.4f, h * 0.06f),
            Offset(w * 0.6f, h * 0.15f),  Offset(w * 0.82f, h * 0.08f),
            Offset(w * 0.28f, h * 0.22f),
        ).forEach { drawCircle(Color.White, radius = 1.8f, center = it) }

        // Moon crescent
        drawCircle(Color(0xFFEEEEFF), radius = h * 0.085f, center = Offset(w * 0.77f, h * 0.18f))
        drawCircle(Color(0xFF1A1A45),  radius = h * 0.07f,  center = Offset(w * 0.82f, h * 0.15f))

        // Ground
        drawRect(Color(0xFF1B2631), topLeft = Offset(0f, h * 0.72f), size = Size(w, h * 0.28f))

        val wallC   = Color(0xFF37474F)
        val roofC   = Color(0xFF263238)
        val winC    = Color(0xFFFFEE58)  // lit windows
        val doorC   = Color(0xFF1A1A2E)
        val bLeft   = w * 0.08f; val bRight = w * 0.92f
        val bBottom = h * 0.72f; val bTop   = h * 0.44f

        drawRect(wallC, topLeft = Offset(bLeft, bTop), size = Size(bRight - bLeft, bBottom - bTop))
        val roof = Path().apply {
            moveTo(w * 0.03f, bTop); lineTo(w * 0.5f, h * 0.2f); lineTo(w * 0.97f, bTop); close()
        }
        drawPath(roof, roofC)
        val dW = w * 0.17f; val dH = h * 0.2f
        drawRect(doorC, topLeft = Offset(w * 0.5f - dW / 2, bBottom - dH), size = Size(dW, dH))
        val wW = w * 0.17f; val wH = h * 0.12f; val wTop = bTop + h * 0.08f
        drawRect(winC, topLeft = Offset(w * 0.16f, wTop), size = Size(wW, wH))
        drawRect(winC, topLeft = Offset(w * 0.67f, wTop), size = Size(wW, wH))
        // Window glow
        drawRect(winC.copy(alpha = 0.15f), topLeft = Offset(w * 0.1f, wTop), size = Size(wW * 1.8f, wH * 1.8f))
    }
}

// ─── Radio-button option row ──────────────────────────────────────────────────

@Composable
private fun NightModeOptionRow(
    title: String,
    description: String,
    selected: Boolean,
    primary: Color,
    onClick: () -> Unit,
) {
    val titleColor by animateColorAsState(
        targetValue    = if (selected) primary else MaterialTheme.colorScheme.onSurface,
        animationSpec  = tween(180),
        label          = "titleColor",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Icon area placeholder (keeps alignment consistent)
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = titleColor)
            if (description.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(description, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 16.sp)
            }
        }
        RadioButton(
            selected = selected,
            onClick  = onClick,
            colors   = RadioButtonDefaults.colors(selectedColor = primary),
        )
    }
}

// ─── Global (all-channels) view — unchanged ───────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GlobalOsdContent(
    stream: JSONObject,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        // ── Channel names ──────────────────────────────────────────────────
        val titles = stream.optJSONArray("Title")
        if (titles != null) {
            Text("Channel Names", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(
                "Overlay text shown on each camera's video.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    for (i in 0 until titles.length()) {
                        val t  = titles.getJSONObject(i)
                        val id = t.optInt("ID", i)
                        var txt by remember(id) { mutableStateOf(t.optString("Text")) }
                        OutlinedTextField(
                            value         = txt,
                            onValueChange = { txt = it; t.put("Text", it) },
                            label         = { Text("Channel ${id + 1}") },
                            singleLine    = true,
                            modifier      = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        )
                    }
                }
            }
        }

        // ── Channel-status overlay switch ──────────────────────────────────
        val osd = stream.optJSONObject("OSD")
        if (osd != null) {
            Spacer(Modifier.height(20.dp))
            Text("Overlay Options", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Spacer(Modifier.height(8.dp))
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                var chnStatus by remember { mutableStateOf(osd.optString("ChnStatus") == "Enable") }
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Channel-status badge",
                            fontWeight = FontWeight.Medium, fontSize = 14.sp)
                        Text("Show the status overlay on each channel.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = chnStatus,
                        onCheckedChange = {
                            chnStatus = it
                            osd.put("ChnStatus", if (it) "Enable" else "Disable")
                        },
                    )
                }
            }
        }

        // ── IR Cut all channels ────────────────────────────────────────────
        val ircut = stream.optJSONArray("Ircut")
        if (ircut != null && ircut.length() > 0) {
            Spacer(Modifier.height(20.dp))
            Text("Day / Night Mode", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(
                "IR-cut mode per camera channel.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    for (i in 0 until ircut.length()) {
                        val ch    = ircut.getJSONObject(i)
                        val id    = ch.optInt("ID", i)
                        val range = ch.optJSONArray("IrcutModeRange")
                        val opts  = range?.let { arr ->
                            (0 until arr.length()).mapNotNull {
                                arr.optString(it).takeIf { s -> s.isNotBlank() }
                            }
                        } ?: emptyList()
                        if (opts.isNotEmpty()) {
                            if (i > 0) HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                            Text("Channel ${id + 1}", fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(vertical = 6.dp))
                            GlobalModeDropdown("IR-cut mode", ch.optString("IrcutModeCur"), opts) {
                                ch.put("IrcutModeCur", it)
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        Button(
            onClick  = onSave,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape    = RoundedCornerShape(12.dp),
        ) { Text("Apply", fontSize = 15.sp) }
        Spacer(Modifier.height(24.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GlobalModeDropdown(
    label: String,
    value: String,
    options: List<String>,
    onPick: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var current  by remember(value) { mutableStateOf(value) }
    ExposedDropdownMenuBox(
        expanded         = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier         = Modifier.padding(bottom = 6.dp),
    ) {
        OutlinedTextField(
            value         = current,
            onValueChange = {},
            readOnly      = true,
            label         = { Text(label) },
            trailingIcon  = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier      = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { opt ->
                DropdownMenuItem(
                    text    = { Text(opt) },
                    onClick = { current = opt; onPick(opt); expanded = false },
                )
            }
        }
    }
}
