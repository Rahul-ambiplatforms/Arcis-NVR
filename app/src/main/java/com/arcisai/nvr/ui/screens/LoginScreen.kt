package com.arcisai.nvr.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arcisai.nvr.R
import com.arcisai.nvr.ui.theme.AccentPurple
import com.arcisai.nvr.ui.theme.loginGradient
import com.arcisai.nvr.viewmodel.NvrViewModel

@Composable
fun LoginScreen(
    vm: NvrViewModel,
    onCloudAuthenticated: () -> Unit,
) {
    var email  by remember { mutableStateOf("") }
    var pwd    by remember { mutableStateOf("") }
    var pwdVis by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier.fillMaxSize().background(loginGradient()),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BrandHeader()
            Spacer(Modifier.height(28.dp))

            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 4.dp,
                modifier = Modifier.fillMaxWidth().shadow(8.dp, RoundedCornerShape(20.dp)),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        "Login",
                        fontWeight = FontWeight.SemiBold, fontSize = 18.sp,
                    )
                    Text(
                        "Use your Arcis account. After signing in you'll see every NVR linked to your account.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    OutlinedTextField(
                        value = email, onValueChange = { email = it.trim() },
                        label = { Text("Email") }, singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    )
                    OutlinedTextField(
                        value = pwd, onValueChange = { pwd = it },
                        label = { Text("Password") }, singleLine = true,
                        visualTransformation = if (pwdVis) VisualTransformation.None else PasswordVisualTransformation(),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { pwdVis = !pwdVis }) {
                                Icon(
                                    imageVector = if (pwdVis) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = if (pwdVis) "Hide password" else "Show password",
                                )
                            }
                        },
                    )

                    vm.loginStatus?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                    }
                    vm.remoteStatus?.let {
                        Text(it, color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                    }

                    Spacer(Modifier.height(4.dp))
                    Button(
                        onClick = { vm.accountLogin(email, pwd, onCloudAuthenticated) },
                        enabled = !vm.loginBusy && email.isNotBlank() && pwd.isNotBlank(),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) {
                        if (vm.loginBusy) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(20.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Text("Login", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            Text(
                "ArcisAI · NVR companion",
                fontSize = 11.sp,
                color = Color(0xFF8E8AA0),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun BrandHeader() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Image(
            painter = painterResource(R.drawable.arcisai_full_logo),
            contentDescription = "ArcisAI",
            modifier = Modifier.height(56.dp).widthIn(max = 240.dp),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "NVR",
            color = AccentPurple,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            letterSpacing = 4.sp,
        )
    }
}
