package com.example.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.InsightsData
import com.example.network.HermesApiClient
import kotlinx.coroutines.launch

private fun formatTokens(n: Int): String {
    return when {
        n >= 1_000_000 -> String.format("%.1fM", n / 1_000_000.0)
        n >= 1_000 -> String.format("%.1fK", n / 1_000.0)
        else -> n.toString()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    apiClient: HermesApiClient,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    var insights by remember { mutableStateOf<InsightsData?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }

    fun loadInsights() {
        scope.launch {
            isLoading = true
            errorMessage = ""
            try {
                insights = apiClient.getInsights()
            } catch (e: Exception) {
                errorMessage = e.message ?: "Failed to load"
            }
            isLoading = false
        }
    }

    LaunchedEffect(Unit) { loadInsights() }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = {
                    Text("Stats", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                actions = {
                    IconButton(onClick = { loadInsights() }, enabled = !isLoading) {
                        if (isLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp, color = Color.White)
                        } else {
                            Icon(Icons.Outlined.Refresh, contentDescription = "Refresh", tint = Color.White)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black),
                modifier = Modifier.statusBarsPadding()
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when {
                isLoading && insights == null -> {
                    CircularProgressIndicator(
                        color = Color(0xFFFFD700),
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                errorMessage.isNotBlank() && insights == null -> {
                    Text(
                        text = errorMessage,
                        color = Color(0xFFFF6B6B),
                        fontSize = 14.sp,
                        modifier = Modifier.align(Alignment.Center).padding(32.dp)
                    )
                }
                else -> {
                    val data = insights ?: return@Box
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        if (errorMessage.isNotBlank()) {
                            Text(errorMessage, color = Color(0xFFFF6B6B), fontSize = 13.sp)
                        }

                        OverviewCard(data)
                        TokensCard(data)
                        ModelsCard(data)
                    }
                }
            }
        }
    }
}

@Composable
private fun OverviewCard(data: InsightsData) {
    Surface(
        color = Color(0xFF1E1E1E),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Analytics, contentDescription = null, tint = Color(0xFFFFD700), modifier = Modifier.size(22.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Text("Overview", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.weight(1f))
                Text("${data.periodDays}d", color = Color(0xFF666666), fontSize = 13.sp)
            }
            Spacer(modifier = Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                StatItem("Sessions", data.totalSessions.toString())
                StatItem("Messages", data.totalMessages.toString())
            }
        }
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(4.dp))
        Text(label, color = Color(0xFF888888), fontSize = 14.sp)
    }
}

@Composable
private fun TokensCard(data: InsightsData) {
    Surface(
        color = Color(0xFF1E1E1E),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Token, contentDescription = null, tint = Color(0xFFFFD700), modifier = Modifier.size(22.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Text("Tokens", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(modifier = Modifier.height(16.dp))
            TokenRow("Input", data.totalInputTokens, Color(0xFF4CAF50))
            Spacer(modifier = Modifier.height(10.dp))
            TokenRow("Output", data.totalOutputTokens, Color(0xFF64B5F6))
            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = Color(0xFF2C2C2C))
            Spacer(modifier = Modifier.height(10.dp))
            TokenRow("Total", data.totalTokens, Color.White)
        }
    }
}

@Composable
private fun TokenRow(label: String, value: Int, valueColor: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Color(0xFF888888), fontSize = 15.sp)
        Text(formatTokens(value), color = valueColor, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ModelsCard(data: InsightsData) {
    Surface(
        color = Color(0xFF1E1E1E),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.SmartToy, contentDescription = null, tint = Color(0xFFFFD700), modifier = Modifier.size(22.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Text("Models", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(modifier = Modifier.height(16.dp))

            if (data.models.isEmpty()) {
                Text("No data", color = Color(0xFF555555), fontSize = 14.sp)
            } else {
                data.models.forEachIndexed { index, model ->
                    ModelRow(model)
                    if (index < data.models.lastIndex) {
                        Spacer(modifier = Modifier.height(14.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelRow(model: com.example.data.ModelStats) {
    val maxShare = model.tokenShare.coerceAtLeast(1)
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = model.model.substringAfterLast('/'),
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "${formatTokens(model.totalTokens)} (${model.tokenShare}%)",
                color = Color(0xFFBBBBBB),
                fontSize = 13.sp
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color(0xFF2C2C2C))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction = maxShare / 100f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color(0xFFFFD700))
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${model.sessions} sessions", color = Color(0xFF666666), fontSize = 12.sp)
            Text("in: ${formatTokens(model.inputTokens)}  out: ${formatTokens(model.outputTokens)}", color = Color(0xFF666666), fontSize = 12.sp)
        }
    }
}
