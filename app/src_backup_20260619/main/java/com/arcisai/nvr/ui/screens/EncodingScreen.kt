package com.arcisai.nvr.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.*
import androidx.compose.material3.MenuAnchorType
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arcisai.nvr.viewmodel.NvrViewModel
import org.json.JSONArray
import org.json.JSONObject

private val BITRATE_OPTIONS = listOf("64kbps","128kbps","256kbps","512kbps","1Mbps","2Mbps","4Mbps","8Mbps")
private val CODEC_OPTIONS   = listOf("H.264","H.264+","H.265","H.265+","MJPEG")
private val FPS_OPTIONS     = listOf("5fps","10fps","15fps","20fps","25fps","30fps")
private val BMODE_OPTIONS   = listOf("Variable","Constant")
private val RES_OPTIONS     = listOf(
    "2560x1440","2304x1296","1920x1080","1280x960","1280x720",
    "800x600","800x448","640x480","320x240",
)

/**
 * [channelId] != null  → per-channel Stream Quality view (from Channel Settings).
 * [channelId] == null  → all-channel encoding (from NVR Settings hub).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EncodingScreen(vm: NvrViewModel, onBack: () -> Unit, channelId: Int? = null) {
    LaunchedEffect(Unit) { vm.loadEncode() }
    val snack = rememberSettingsSnackbar(vm.settingStatus)

    val title = if (channelId != null) "Stream Quality – Ch ${channelId + 1}" else "Camera Encoding"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { padding ->
        val arr = vm.encodeCfg
        when {
            arr == null -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            else -> {
                val entries  = (0 until arr.length()).map { arr.getJSONObject(it) }
                val visible  = if (channelId != null)
                    entries.filter { it.optInt("ID") == channelId }
                else entries

                Column(
                    modifier = Modifier
                        .padding(padding)
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (channelId == null) {
                        // All-channel view: grouped by channel
                        visible.forEach { ch ->
                            AllChannelEncodeCard(ch, onSave = { vm.saveEncode(arr) })
                        }
                    } else {
                        // Per-channel: cleaner single-channel layout
                        visible.forEach { ch ->
                            PerChannelEncodeContent(ch, onSave = { vm.saveEncode(arr) })
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

// ─── Per-channel view (from Channel Settings) ─────────────────────────────────

@Composable
private fun PerChannelEncodeContent(ch: JSONObject, onSave: () -> Unit) {
    val streams: JSONArray = ch.optJSONArray("Stream") ?: JSONArray()

    for (s in 0 until streams.length()) {
        val stream     = streams.getJSONObject(s)
        val streamName = stream.optString("Name", "Stream ${s + 1}")
        val isMain     = s == 0

        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape    = RoundedCornerShape(14.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 12.dp),
                ) {
                    Icon(Icons.Default.Speed, null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            if (isMain) "Main Stream" else "Sub Stream",
                            fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                        )
                        Text(
                            if (isMain) "High quality — recording & full-res live"
                            else        "Low bandwidth — remote preview & mobile",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))

                EncodingDropdown("Resolution", stream.optString("Format"), RES_OPTIONS) {
                    stream.put("Format", it)
                }
                EncodingDropdown("Codec", stream.optString("CodingFmt"), CODEC_OPTIONS) {
                    stream.put("CodingFmt", it)
                }
                EncodingDropdown("Bitrate Mode", stream.optString("BitrateMode"), BMODE_OPTIONS) {
                    stream.put("BitrateMode", it)
                }
                EncodingDropdown("Bitrate", stream.optString("BitrateValue"), BITRATE_OPTIONS) {
                    stream.put("BitrateValue", it)
                }
                EncodingDropdown("Frame Rate", stream.optString("Framerate"), FPS_OPTIONS) {
                    stream.put("Framerate", it)
                }
            }
        }
    }

    Button(
        onClick  = onSave,
        modifier = Modifier.fillMaxWidth().height(50.dp),
        shape    = RoundedCornerShape(12.dp),
    ) { Text("Apply Changes", fontSize = 15.sp) }
}

// ─── All-channel view (from NVR Settings) ────────────────────────────────────

@Composable
private fun AllChannelEncodeCard(ch: JSONObject, onSave: () -> Unit) {
    val id      = ch.optInt("ID")
    val streams = ch.optJSONArray("Stream") ?: JSONArray()

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Channel ${id + 1}", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            for (s in 0 until streams.length()) {
                val stream = streams.getJSONObject(s)
                val name   = stream.optString("Name", if (s == 0) "Main Stream" else "Sub Stream")
                Text(name, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                EncodingDropdown("Resolution",   stream.optString("Format"),       RES_OPTIONS)   { stream.put("Format",       it) }
                EncodingDropdown("Codec",         stream.optString("CodingFmt"),    CODEC_OPTIONS) { stream.put("CodingFmt",    it) }
                EncodingDropdown("Bitrate Mode",  stream.optString("BitrateMode"),  BMODE_OPTIONS) { stream.put("BitrateMode",  it) }
                EncodingDropdown("Bitrate",       stream.optString("BitrateValue"), BITRATE_OPTIONS){ stream.put("BitrateValue", it) }
                EncodingDropdown("Frame Rate",    stream.optString("Framerate"),    FPS_OPTIONS)   { stream.put("Framerate",    it) }
                if (s < streams.length() - 1) {
                    Spacer(Modifier.height(4.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(
                onClick  = onSave,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Apply") }
        }
    }
}

// ─── Shared dropdown ─────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EncodingDropdown(
    label: String,
    value: String,
    options: List<String>,
    onPick: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var current  by remember(value) { mutableStateOf(value) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
    ) {
        OutlinedTextField(
            value         = current,
            onValueChange = {},
            readOnly      = true,
            label         = { Text(label) },
            trailingIcon  = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier      = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            colors        = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
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
