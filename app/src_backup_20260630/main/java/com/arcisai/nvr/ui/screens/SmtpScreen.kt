package com.arcisai.nvr.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arcisai.nvr.viewmodel.NvrViewModel

private val SMTP_PROVIDERS  = listOf("gmail", "yahoo", "hotmail", "126", "163", "qq", "custom")
private val ENCRYPT_OPTIONS = listOf("None", "SSL", "TLS", "STARTTLS")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmtpScreen(vm: NvrViewModel, onBack: () -> Unit) {
    LaunchedEffect(Unit) { vm.loadSmtp() }

    val cfg   = vm.smtpCfg
    val snack = rememberSettingsSnackbar(vm.settingStatus)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Email Alerts", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        Column(
            modifier = Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            if (cfg == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                return@Column
            }

            var smtpUse   by remember(cfg) { mutableStateOf(nvrBool(cfg.optString("SMTPUse"))) }
            var provider  by remember(cfg) { mutableStateOf(cfg.optString("SMTPProvider")) }
            var server    by remember(cfg) { mutableStateOf(cfg.optString("SMTPServer")) }
            var port      by remember(cfg) { mutableStateOf(cfg.optInt("SMTPPort", 25)) }
            var encrypt   by remember(cfg) { mutableStateOf(cfg.optString("SMTPEncryptType")) }
            var sender    by remember(cfg) { mutableStateOf(cfg.optString("SMTPSender")) }
            var pwd       by remember(cfg) { mutableStateOf(cfg.optString("SMTPPwd")) }
            var showPwd   by remember { mutableStateOf(false) }
            var to1       by remember(cfg) { mutableStateOf(cfg.optString("SMTPSendee1")) }
            var to2       by remember(cfg) { mutableStateOf(cfg.optString("SMTPSendee2")) }
            var subject   by remember(cfg) { mutableStateOf(cfg.optString("SMTPSubject")) }
            var interval  by remember(cfg) { mutableStateOf(cfg.optInt("SMTPInterval", 30)) }
            var health    by remember(cfg) { mutableStateOf(nvrBool(cfg.optString("SMTPHealthEnable"))) }

            Spacer(Modifier.height(12.dp))

            // ── Enable toggle ─────────────────────────────────────────────────
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape    = RoundedCornerShape(12.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Send alerts by email",
                            fontWeight = FontWeight.Medium, fontSize = 15.sp)
                        Text("Motion and alarm events trigger an email",
                            fontSize = 12.sp,
                            color    = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = smtpUse, onCheckedChange = { smtpUse = it })
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── Mail Server ───────────────────────────────────────────────────
            SmtpSectionLabel("Mail Server")
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape    = RoundedCornerShape(12.dp),
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    SmtpDropdown("Provider", provider, SMTP_PROVIDERS) { provider = it }
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    SmtpTextField("Server", server) { server = it }
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    SmtpNumberField("Port", port) { port = it }
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    SmtpDropdown("Encryption", encrypt, ENCRYPT_OPTIONS) { encrypt = it }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── Account ───────────────────────────────────────────────────────
            SmtpSectionLabel("Account")
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape    = RoundedCornerShape(12.dp),
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    SmtpTextField("Sender email", sender,
                        keyboardType = KeyboardType.Email) { sender = it }
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    // Password row with show/hide
                    Row(
                        modifier          = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value            = pwd,
                            onValueChange    = { pwd = it },
                            label            = { Text("Password") },
                            singleLine       = true,
                            modifier         = Modifier.weight(1f).padding(vertical = 4.dp),
                            visualTransformation = if (showPwd) VisualTransformation.None
                                                   else PasswordVisualTransformation(),
                            keyboardOptions  = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = KeyboardType.Password),
                            trailingIcon     = {
                                IconButton(onClick = { showPwd = !showPwd }) {
                                    Icon(
                                        if (showPwd) Icons.Default.VisibilityOff
                                        else Icons.Default.Visibility,
                                        contentDescription = null,
                                    )
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                                focusedBorderColor   = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                            ),
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── Recipients ────────────────────────────────────────────────────
            SmtpSectionLabel("Recipients")
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape    = RoundedCornerShape(12.dp),
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    SmtpTextField("To (primary)", to1,
                        keyboardType = KeyboardType.Email) { to1 = it }
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    SmtpTextField("To (secondary)", to2,
                        keyboardType = KeyboardType.Email) { to2 = it }
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    SmtpTextField("Subject", subject) { subject = it }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── Options ───────────────────────────────────────────────────────
            SmtpSectionLabel("Options")
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape    = RoundedCornerShape(12.dp),
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    SmtpNumberField("Min gap between alerts (seconds)", interval) { interval = it }
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    Row(
                        modifier          = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Periodic health check email",
                                fontWeight = FontWeight.Medium, fontSize = 15.sp)
                            Text("Sends a test email periodically to confirm alerts work",
                                fontSize = 12.sp,
                                color    = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = health, onCheckedChange = { health = it })
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick  = { vm.testSmtp() },
                    modifier = Modifier.weight(1f),
                ) { Text("Send test") }
                Button(
                    onClick = {
                        cfg.put("SMTPUse",           nvrStrBool(smtpUse))
                        cfg.put("SMTPProvider",      provider)
                        cfg.put("SMTPServer",        server)
                        cfg.put("SMTPPort",          port)
                        cfg.put("SMTPEncryptType",   encrypt)
                        cfg.put("SMTPSender",        sender)
                        cfg.put("SMTPPwd",           pwd)
                        cfg.put("SMTPSendee1",       to1)
                        cfg.put("SMTPSendee2",       to2)
                        cfg.put("SMTPSubject",       subject)
                        cfg.put("SMTPInterval",      interval)
                        cfg.put("SMTPHealthEnable",  nvrStrBool(health))
                        vm.saveSmtp(cfg)
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Save") }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

// ── local helpers ─────────────────────────────────────────────────────────────

@Composable
private fun SmtpSectionLabel(text: String) {
    Text(
        text,
        fontSize   = 12.sp,
        fontWeight = FontWeight.Medium,
        color      = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier   = Modifier.padding(start = 4.dp, bottom = 6.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SmtpTextField(
    label: String,
    value: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value           = value,
        onValueChange   = onChange,
        label           = { Text(label) },
        singleLine      = true,
        modifier        = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = keyboardType),
        shape           = RoundedCornerShape(10.dp),
        colors          = OutlinedTextFieldDefaults.colors(
            unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
            focusedBorderColor   = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
        ),
    )
}

@Composable
private fun SmtpNumberField(label: String, value: Int, onChange: (Int) -> Unit) {
    OutlinedTextField(
        value           = value.toString(),
        onValueChange   = { onChange(it.toIntOrNull() ?: value) },
        label           = { Text(label) },
        singleLine      = true,
        modifier        = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = KeyboardType.Number),
        shape           = RoundedCornerShape(10.dp),
        colors          = OutlinedTextFieldDefaults.colors(
            unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
            focusedBorderColor   = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SmtpDropdown(
    label: String,
    value: String,
    options: List<String>,
    onChange: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded         = expanded,
        onExpandedChange = { expanded = it },
        modifier         = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value                  = value,
            onValueChange          = {},
            readOnly               = true,
            label                  = { Text(label) },
            trailingIcon           = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier               = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .padding(vertical = 4.dp),
            shape                  = RoundedCornerShape(10.dp),
            colors                 = OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                focusedBorderColor   = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
            ),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { opt ->
                DropdownMenuItem(
                    text    = { Text(opt) },
                    onClick = { onChange(opt); expanded = false },
                )
            }
        }
    }
}
