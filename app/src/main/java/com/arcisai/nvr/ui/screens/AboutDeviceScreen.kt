package com.arcisai.nvr.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arcisai.nvr.viewmodel.NvrViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutDeviceScreen(
    vm: NvrViewModel,
    onBack: () -> Unit,
) {
    val info = vm.deviceInfo
    val bg   = MaterialTheme.colorScheme.background

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("About Device", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
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
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {

            // ── Device Information ─────────────────────────────────────────
            item {
                AboutSection(title = "Device Information") {
                    val model = info?.optString("DeviceModel")?.ifBlank { "—" } ?: "—"
                    val fw    = info?.optString("FWVersion")?.ifBlank { "—" }   ?: "—"
                    val uid   = info?.optString("UID")
                        ?.ifBlank { info.optString("HWID").ifBlank { "—" } }     ?: "—"

                    AboutRow("Device Model",    model, last = false)
                    AboutRow("Device Type",     "NVR", last = false)
                    AboutRow("Device ID",       uid,   last = false)
                    AboutRow("Firmware Version", fw,   last = true)
                }
            }

            // ── Network Information ────────────────────────────────────────
            item {
                AboutSection(title = "Network Information") {
                    val ip = vm.credentials?.host?.ifBlank { "—" } ?: "—"
                    AboutRow("IP Address", ip, last = true)
                }
            }
        }
    }
}

@Composable
private fun AboutSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(
            title,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
            fontSize = 13.sp,
            color    = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Card(
            modifier  = Modifier.fillMaxWidth(),
            shape     = RoundedCornerShape(12.dp),
            colors    = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(0.dp),
        ) {
            Column(content = content)
        }
    }
}

@Composable
private fun AboutRow(label: String, value: String, last: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 15.sp)
        Text(value, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (!last) {
        HorizontalDivider(
            modifier  = Modifier.padding(start = 16.dp),
            thickness = 0.5.dp,
            color     = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}
