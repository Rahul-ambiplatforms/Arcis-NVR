package com.arcisai.nvr.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arcisai.nvr.viewmodel.NvrViewModel

/**
 * Users — read-only list of NVR accounts (/netsdk/User), rendered generically
 * from whatever the firmware returns (user name + group/authority). Add / edit
 * / delete are intentionally deferred: they mutate live NVR auth (risk of
 * locking out admin) and the AddUser/DelUser Parameter schema isn't verified
 * against this firmware yet. To change the admin password, use Settings →
 * Change password.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsersScreen(vm: NvrViewModel, onBack: () -> Unit) {
    LaunchedEffect(Unit) { vm.loadUsers() }
    val cfg = vm.usersCfg
    val snack = rememberSettingsSnackbar(vm.settingStatus)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Users", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            SettingsStatusBar(vm.settingStatus)
            if (cfg == null) {
                Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                return@Column
            }

            Text("Accounts configured on the NVR. Read-only for now — use " +
                "“Change password” to update the admin credential.",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

            val users = firstArray(cfg)
            if (users != null && users.length() > 0) {
                for (i in 0 until users.length()) {
                    val u = users.optJSONObject(i) ?: continue
                    ElevatedCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                        JsonScalarRows(u)
                        Spacer(Modifier.height(4.dp))
                    }
                }
            } else {
                // Fall back to scalar fields if the firmware nests differently.
                JsonScalarRows(cfg)
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}
