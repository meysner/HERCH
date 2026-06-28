package com.example.ui.screens

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import com.example.data.LoginResult
import com.example.network.HermesAuthClient

private const val PREFS_NAME = "herch_hermes"
private const val PREF_WEBUI_URL = "webui_url"

@Composable
fun HermesLoginScreen(
    httpClient: okhttp3.OkHttpClient,
    onLoginSuccess: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    val authClient = remember { HermesAuthClient(httpClient) }
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(prefs.getString(PREF_WEBUI_URL, "") ?: "") }
    var password by remember { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }

    fun handleResult(result: LoginResult, navigateOnSuccess: Boolean) {
        when (result) {
            is LoginResult.Success -> {
                isError = false
                statusMessage = result.message
                val normalized = runCatching { authClient.normalizeBaseUrl(url) }.getOrNull()
                if (normalized != null) {
                    prefs.edit().putString(PREF_WEBUI_URL, normalized).apply()
                    url = normalized
                }
                if (navigateOnSuccess) {
                    onLoginSuccess()
                }
            }
            is LoginResult.Failure -> {
                isError = true
                statusMessage = result.message
            }
        }
    }

    Scaffold(
        containerColor = Color.Black,
        contentWindowInsets = WindowInsets(0)
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.weight(1f))

            Text(
                text = "HERCH",
                color = Color(0xFFFFD700),
                fontSize = 36.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 2.sp,
                style = TextStyle(
                    shadow = Shadow(
                        color = Color(0x80B8860B),
                        offset = Offset(4f, 4f),
                        blurRadius = 8f
                    )
                )
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Connect to Hermes WebUI",
                color = Color(0xFFBBBBBB),
                fontSize = 16.sp,
            )

            Spacer(modifier = Modifier.height(48.dp))

            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("WebUI URL") },
                placeholder = { Text("https://agent.example.com") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Uri),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFFFFD700),
                    focusedLabelColor = Color(0xFFFFD700),
                    unfocusedBorderColor = Color.DarkGray,
                    unfocusedLabelColor = Color.Gray,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    cursorColor = Color(0xFFFFD700)
                ),
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("WebUI password") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Password),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFFFFD700),
                    focusedLabelColor = Color(0xFFFFD700),
                    unfocusedBorderColor = Color.DarkGray,
                    unfocusedLabelColor = Color.Gray,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    cursorColor = Color(0xFFFFD700)
                ),
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.height(40.dp))

            Button(
                onClick = {
                    scope.launch {
                        isLoading = true
                        statusMessage = ""
                        handleResult(authClient.checkConnection(url), navigateOnSuccess = false)
                        isLoading = false
                    }
                },
                enabled = !isLoading,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF222222), contentColor = Color.White),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = Color.White
                    )
                } else {
                    Icon(Icons.Outlined.Cloud, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Check connection", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    scope.launch {
                        isLoading = true
                        statusMessage = ""
                        handleResult(authClient.login(url, password), navigateOnSuccess = true)
                        isLoading = false
                    }
                },
                enabled = !isLoading,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFD700), contentColor = Color.Black),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Login and save", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }

            if (statusMessage.isNotBlank()) {
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = statusMessage,
                    color = if (isError) Color(0xFFFF6B6B) else Color(0xFF7ED957),
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.weight(1f))
        }
    }
}
