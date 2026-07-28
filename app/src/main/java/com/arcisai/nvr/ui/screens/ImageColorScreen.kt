package com.arcisai.nvr.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arcisai.nvr.viewmodel.NvrViewModel
import org.json.JSONObject

private val KNOWN_NUMBER_ENUMS: Map<String, List<Int>> = mapOf(
    "imageStyle" to listOf(1, 2, 3, 4, 5),
)
private val KNOWN_ENUMS: Map<String, List<String>> = mapOf(
    "sceneMode"           to listOf("indoor", "outdoor", "auto"),
    "exposureMode"        to listOf("auto", "manual"),
    "awbMode"             to listOf("indoor", "outdoor", "auto", "manual"),
    "lowlightMode"        to listOf("close", "only night", "always"),
    "BLcompensationMode"  to listOf("auto", "close", "open"),
)
private val WRITABLE_FIELDS: Set<String> = setOf(
    "brightness", "contrast", "saturation", "sharpness", "hue", "imageStyle",
)
private val KNOWN_RANGES: Map<String, IntRange> = mapOf(
    "brightness" to 0..100,
    "contrast"   to 0..100,
    "saturation" to 0..100,
    "sharpness"  to 0..100,
    "hue"        to 0..100,
)

/**
 * [channelId] != null  → per-channel Image & Color (from Channel Settings).
 * [channelId] == null  → all-channel view with tab selector (from NVR Settings).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageColorScreen(
    vm: NvrViewModel,
    onBack: () -> Unit,
    channelId: Int? = null,
) {
    val channels = vm.channels
    val snack    = rememberSettingsSnackbar(vm.settingStatus)

    val fixedIdx = channelId?.let { id -> channels.indexOfFirst { it.id == id }.takeIf { it >= 0 } }
    var selected by remember(channels.size, channelId) { mutableStateOf(fixedIdx ?: 0) }

    LaunchedEffect(selected, channels.size) {
        channels.getOrNull(selected.coerceAtLeast(0))?.let { vm.loadColorFor(it.id) }
    }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, selected, channels.size) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                channels.getOrNull(selected.coerceAtLeast(0))?.let { vm.loadColorFor(it.id) }
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    val safeIdx = if (channels.isEmpty()) 0 else selected.coerceIn(0, channels.size - 1)
    val ch = channels.getOrNull(safeIdx)

    val title = if (channelId != null) "Image & Color – Ch ${channelId + 1}" else "Image & Color"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { ch?.let { vm.loadColorFor(it.id) } }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reload")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            if (channels.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                    Text("No channels loaded.")
                }
                return@Column
            }

            // Channel tab selector — only in all-channels (global NVR settings) view
            if (fixedIdx == null) {
                Row(
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (i in channels.indices) {
                        val active = i == safeIdx
                        Surface(
                            shape  = RoundedCornerShape(50),
                            color  = if (active) MaterialTheme.colorScheme.primary
                                     else        MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(50))
                                .clickable { selected = i },
                        ) {
                            Box(
                                modifier = Modifier.padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "Ch ${i + 1}",
                                    fontSize     = 13.sp,
                                    fontWeight   = FontWeight.SemiBold,
                                    color        = if (active) MaterialTheme.colorScheme.onPrimary
                                                   else        MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            } else {
                Spacer(Modifier.height(8.dp))
            }

            val cfg = ch?.let { vm.perChannelColor[it.id] }
            val loadFailed = ch?.id?.let { it in vm.perChannelColorFailed } == true
            if (cfg == null) {
                Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                    if (loadFailed) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(horizontal = 24.dp),
                        ) {
                            Icon(
                                Icons.Default.CloudOff, null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(16.dp))
                            Text("Camera offline", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Image & color settings require a live connection to the camera.",
                                textAlign = TextAlign.Center,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(20.dp))
                            OutlinedButton(onClick = { vm.loadColorFor(ch!!.id) }) {
                                Text("Retry")
                            }
                        }
                    } else {
                        CircularProgressIndicator()
                    }
                }
                return@Column
            }

            if (channelId != null) {
                // Per-channel: clean card layout
                PerChannelColorContent(cfg) { changes -> ch?.id?.let { id -> vm.saveColorFor(id, changes) } }
            } else {
                // All-channel: existing dynamic form with section labels
                DynamicImageForm(cfg) { changes -> ch?.id?.let { id -> vm.saveColorFor(id, changes) } }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

// ─── Per-channel redesigned view ─────────────────────────────────────────────

@Composable
private fun PerChannelColorContent(cfg: JSONObject, onApply: (JSONObject) -> Unit) {
    val pending = remember(cfg) { mutableStateMapOf<String, Any>() }

    val allKeys      = remember(cfg) { cfg.keys().asSequence().toList() }
    val writableKeys = allKeys.filter { it in WRITABLE_FIELDS }.sortedBy { fieldOrder(it) }
    val readOnlyKeys = allKeys.filter { it !in WRITABLE_FIELDS }.sorted()

    Column(modifier = Modifier.padding(horizontal = 16.dp)) {

        // ── Adjustable sliders / pickers ──────────────────────────────────
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape    = RoundedCornerShape(14.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Tune, null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Image Adjustments", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                }
                Spacer(Modifier.height(4.dp))
                if (writableKeys.isEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "This camera's image pipeline is hardware-managed. No adjustable controls are available.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Spacer(Modifier.height(8.dp))
                    for (key in writableKeys) {
                        val raw = cfg.opt(key)
                        when {
                            raw is Number -> {
                                val numOpts = KNOWN_NUMBER_ENUMS[key]
                                if (numOpts != null) {
                                    CardNumberEnumField(key, raw.toInt(), numOpts) { pending[key] = it }
                                } else {
                                    CardSliderField(key, raw.toDouble().toInt()) { pending[key] = it }
                                }
                            }
                            raw is Boolean -> CardSwitchField(key, raw) { pending[key] = it }
                            raw is String  -> {
                                val opts = KNOWN_ENUMS[key]
                                if (opts != null) CardEnumField(key, raw, opts) { pending[key] = it }
                                else CardTextField(key, raw) { pending[key] = it }
                            }
                            else -> Unit
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── Camera Status — friendly read-only summary ────────────────────
        val statusRows = readOnlyKeys.mapNotNull { key ->
            val label = FRIENDLY_KEY_LABELS[key] ?: return@mapNotNull null
            val value = friendlyReadOnlyValue(key, cfg.opt(key)) ?: return@mapNotNull null
            label to value
        }
        if (statusRows.isNotEmpty()) {
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                shape    = RoundedCornerShape(14.dp),
            ) {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.Tune, null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Camera Status", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    }
                    HorizontalDivider()
                    statusRows.forEach { (label, value) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                label,
                                fontSize = 13.sp,
                                color    = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                            ) {
                                Text(
                                    value,
                                    fontSize   = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier   = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // ── Apply button ──────────────────────────────────────────────────
        Button(
            modifier = Modifier.fillMaxWidth().height(50.dp),
            enabled  = pending.isNotEmpty(),
            shape    = RoundedCornerShape(12.dp),
            onClick  = {
                val body = JSONObject()
                for ((k, v) in pending) body.put(k, v)
                onApply(body)
            },
        ) {
            Text(
                if (pending.isEmpty()) "Apply" else "Apply changes",
                fontSize = 15.sp,
            )
        }
    }
}

// Per-channel card controls
@Composable
private fun CardSliderField(label: String, initial: Int, onChange: (Int) -> Unit) {
    var v     by remember(initial) { mutableStateOf(initial) }
    val range = KNOWN_RANGES[label] ?: 0..100
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(prettify(label), modifier = Modifier.weight(1f), fontSize = 14.sp)
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.padding(start = 8.dp),
            ) {
                Text(
                    v.toString(),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        Slider(
            value         = v.toFloat(),
            onValueChange = { nv -> val n = nv.toInt(); if (n != v) { v = n; onChange(n) } },
            valueRange    = range.first.toFloat()..range.last.toFloat(),
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CardNumberEnumField(label: String, initial: Int, options: List<Int>, onChange: (Int) -> Unit) {
    var v       by remember(initial) { mutableStateOf(initial) }
    var expanded by remember { mutableStateOf(false) }
    val strOpts  = options.map { it.toString() }
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Text(prettify(label), fontSize = 14.sp)
        Spacer(Modifier.height(4.dp))
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
            OutlinedTextField(
                value         = v.toString(),
                onValueChange = {},
                readOnly      = true,
                trailingIcon  = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                modifier      = Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
                colors        = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                strOpts.forEach { opt ->
                    DropdownMenuItem(
                        text    = { Text(opt) },
                        onClick = {
                            val n = opt.toIntOrNull() ?: return@DropdownMenuItem
                            v = n; onChange(n); expanded = false
                        },
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun CardSwitchField(label: String, initial: Boolean, onChange: (Boolean) -> Unit) {
    var v by remember(initial) { mutableStateOf(initial) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(prettify(label), fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(checked = v, onCheckedChange = { v = it; onChange(it) })
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CardEnumField(label: String, initial: String, options: List<String>, onChange: (String) -> Unit) {
    var v        by remember(initial) { mutableStateOf(initial) }
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Text(prettify(label), fontSize = 14.sp)
        Spacer(Modifier.height(4.dp))
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
            OutlinedTextField(
                value         = v,
                onValueChange = {},
                readOnly      = true,
                trailingIcon  = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                modifier      = Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
                colors        = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { opt ->
                    DropdownMenuItem(text = { Text(opt) }, onClick = { v = opt; onChange(opt); expanded = false })
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun CardTextField(label: String, initial: String, onChange: (String) -> Unit) {
    var v by remember(initial) { mutableStateOf(initial) }
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Text(prettify(label), fontSize = 14.sp)
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value         = v,
            onValueChange = { v = it; onChange(it) },
            singleLine    = true,
            modifier      = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

// ─── All-channel existing form (unchanged logic, slightly cleaner layout) ────

@Composable
private fun DynamicImageForm(cfg: JSONObject, onApply: (JSONObject) -> Unit) {
    val pending = remember(cfg) { mutableStateMapOf<String, Any>() }
    val allKeys      = remember(cfg) { cfg.keys().asSequence().toList() }
    val writableKeys = allKeys.filter { it in WRITABLE_FIELDS }.sortedBy { fieldOrder(it) }
    val readOnlyKeys = allKeys.filter { it !in WRITABLE_FIELDS }.sorted()

    if (writableKeys.isEmpty() && readOnlyKeys.isEmpty()) {
        Text(
            "This camera doesn't expose any image controls.",
            modifier = Modifier.padding(16.dp),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    if (writableKeys.isNotEmpty()) {
        SectionLabel("Adjustable")
        for (key in writableKeys) {
            val raw = cfg.opt(key)
            when {
                raw is Number -> {
                    val numOpts = KNOWN_NUMBER_ENUMS[key]
                    if (numOpts != null) NumberEnumField(key, raw.toInt(), numOpts) { pending[key] = it }
                    else NumericField(key, raw.toDouble().toInt()) { pending[key] = it }
                }
                raw is Boolean -> BoolField(key, raw) { pending[key] = it }
                raw is String  -> {
                    val opts = KNOWN_ENUMS[key]
                    if (opts != null) EnumField(key, raw, opts) { pending[key] = it }
                    else StringField(key, raw) { pending[key] = it }
                }
                else -> Unit
            }
        }
    } else {
        Text(
            "This camera's image pipeline is auto-managed by the hardware.",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (readOnlyKeys.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        SectionLabel("Camera Status")
        for (key in readOnlyKeys) {
            val raw     = cfg.opt(key)
            val display = when (raw) {
                is org.json.JSONObject -> summariseJson(raw)
                is org.json.JSONArray  -> "${raw.length()} entries"
                null -> "—"
                else -> raw.toString()
            }
            ReadOnlyRow(prettify(key), display)
        }
    }

    Spacer(Modifier.height(16.dp))
    Button(
        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
        enabled  = pending.isNotEmpty(),
        onClick  = {
            val body = JSONObject()
            for ((k, v) in pending) body.put(k, v)
            onApply(body)
        },
    ) { Text(if (pending.isEmpty()) "Apply" else "Apply changes") }
}

// ─── All-channel shared controls (same as before) ────────────────────────────

@Composable
private fun NumericField(label: String, initial: Int, onChange: (Int) -> Unit) {
    var v by remember(initial) { mutableStateOf(initial) }
    val range = KNOWN_RANGES[label] ?: 0..100
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(prettify(label), modifier = Modifier.weight(1f), fontSize = 14.sp)
            Text(v.toString(), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        Slider(
            value         = v.toFloat(),
            onValueChange = { nv -> val n = nv.toInt(); if (n != v) { v = n; onChange(n) } },
            valueRange    = range.first.toFloat()..range.last.toFloat(),
        )
    }
}

@Composable
private fun BoolField(label: String, initial: Boolean, onChange: (Boolean) -> Unit) {
    var v by remember(initial) { mutableStateOf(initial) }
    SwitchSetting(prettify(label), v) { v = it; onChange(it) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EnumField(label: String, initial: String, options: List<String>, onChange: (String) -> Unit) {
    var v by remember(initial) { mutableStateOf(initial) }
    DropdownSetting(prettify(label), v, options) { v = it; onChange(it) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NumberEnumField(label: String, initial: Int, options: List<Int>, onChange: (Int) -> Unit) {
    var v       by remember(initial) { mutableStateOf(initial) }
    val strOpts = options.map { it.toString() }
    DropdownSetting(prettify(label), v.toString(), strOpts) { picked ->
        val n = picked.toIntOrNull() ?: return@DropdownSetting
        v = n; onChange(n)
    }
}

@Composable
private fun StringField(label: String, initial: String, onChange: (String) -> Unit) {
    var v by remember(initial) { mutableStateOf(initial) }
    TextSetting(prettify(label), v) { v = it; onChange(it) }
}

// ─── Shared utilities ─────────────────────────────────────────────────────────

private fun fieldOrder(key: String): Int = when (key) {
    "brightness" -> 0; "contrast" -> 1; "saturation" -> 2
    "sharpness"  -> 3; "hue"      -> 4; "imageStyle"  -> 5
    else -> 100
}

private fun summariseJson(o: org.json.JSONObject): String {
    val parts = mutableListOf<String>()
    for (k in o.keys()) {
        val v = o.opt(k)
        parts += "$k=${when (v) { is org.json.JSONObject -> "{…}"; is org.json.JSONArray -> "[…]"; null -> "null"; else -> v.toString() }}"
    }
    return parts.joinToString(", ")
}

private val FRIENDLY_KEY_LABELS: Map<String, String> = mapOf(
    "BLcompensationMode" to "Backlight Compensation",
    "WDR"                to "Wide Dynamic Range",
    "awbMode"            to "White Balance",
    "denoise3d"          to "Noise Reduction",
    "exposureMode"       to "Exposure",
    "irCut"              to "Night Vision",
    "irCutControlMode"   to "Night Vision",
    "lowlightMode"       to "Low Light Mode",
    "sceneMode"          to "Scene",
    "mirror"             to "Mirror",
    "flip"               to "Flip",
    "manual"             to "Manual Mode",
)

private fun friendlyReadOnlyValue(key: String, raw: Any?): String? {
    if (raw == null) return null
    return when (raw) {
        is Boolean             -> if (raw) "On" else "Off"
        is Number              -> raw.toString()
        is String              -> friendlyString(raw)
        is org.json.JSONObject -> friendlyJsonObject(key, raw)
        else                   -> null
    }
}

private fun friendlyString(s: String): String = when (s.lowercase().trim()) {
    "auto"       -> "Auto"
    "close"      -> "Off"
    "open"       -> "On"
    "only night" -> "Night only"
    "always"     -> "Always on"
    "indoor"     -> "Indoor"
    "outdoor"    -> "Outdoor"
    "manual"     -> "Manual"
    "software"   -> "Auto (software)"
    "hardware"   -> "Hardware"
    "true"       -> "On"
    "false"      -> "Off"
    else         -> s.replaceFirstChar { it.uppercase() }
}

private fun friendlyJsonObject(key: String, o: org.json.JSONObject): String? {
    val k = key.lowercase()
    return when {
        k.contains("wdr") -> {
            val on  = o.optBoolean("enabled", false) || o.optString("enabled").equals("true", true)
            val lvl = o.optInt("WDRStrength", 0)
            if (on) "On${if (lvl > 0) " · Level $lvl" else ""}" else "Off"
        }
        k.contains("denoise") -> {
            val on  = o.optBoolean("enabled", false) || o.optString("enabled").equals("true", true)
            val lvl = o.optInt("denoise3dStrength", 0)
            if (on) "On${if (lvl > 0) " · Level $lvl" else ""}" else "Off"
        }
        k.contains("ircut") || k.contains("ir") -> {
            val mode = o.optString("irCutMode", "").lowercase()
            when (mode) { "auto" -> "Auto"; "day" -> "Day mode"; "night" -> "Night mode"; else -> "Auto" }
        }
        k.contains("manual") -> {
            val on = o.optBoolean("enabled", false) || o.optString("enabled").equals("true", true)
            if (on) "On" else "Off"
        }
        o.has("enabled") -> {
            val on = o.optBoolean("enabled", false) || o.optString("enabled").equals("true", true)
            if (on) "On" else "Off"
        }
        else -> null
    }
}

private fun prettify(camel: String): String {
    val sb = StringBuilder()
    for ((i, c) in camel.withIndex()) {
        if (i > 0 && c.isUpperCase() && !camel[i - 1].isUpperCase()) sb.append(' ')
        sb.append(c)
    }
    val s = sb.toString()
    return s[0].uppercase() + s.substring(1)
}
