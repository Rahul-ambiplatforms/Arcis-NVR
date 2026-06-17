package com.arcisai.nvr.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arcisai.nvr.viewmodel.NvrViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutChannelScreen(
    vm: NvrViewModel,
    channelId: Int,
    onBack: () -> Unit,
) {
    val ch = vm.channels.getOrNull(channelId)
    val isOnline = vm.connectedChannels?.contains(channelId) == true
    val bg = MaterialTheme.colorScheme.background

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("About Channel ${channelId + 1}", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
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

            // ── Camera Information ─────────────────────────────────────────
            item {
                AboutSection(title = "Camera Information") {
                    val model = ch?.modelName?.ifBlank { "—" } ?: "—"
                    val type  = ch?.protocol?.ifBlank { "—" } ?: "—"
                    val status = if (isOnline) "Online" else "Offline"

                    AboutRow("Camera Model",    model,  last = false)
                    AboutRow("Camera Type",     type,   last = false)
                    AboutRow("Status",          status, last = true)
                }
            }

            // ── Network Information ────────────────────────────────────────
            item {
                AboutSection(title = "Network Information") {
                    val ip   = ch?.ipAddr?.ifBlank { "—" } ?: "—"
                    val port = ch?.port?.let { if (it > 0) "$it" else "—" } ?: "—"

                    AboutRow("IP Address", ip,   last = false)
                    AboutRow("Port",       port, last = true)
                }
            }
        }
    }
}
