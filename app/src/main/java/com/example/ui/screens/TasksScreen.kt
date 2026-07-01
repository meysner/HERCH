package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.CronJob
import com.example.data.CronJobStatus
import com.example.network.HermesApiClient
import com.example.ui.components.PullDownRefresh
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun formatJobTime(epochMs: Long?): String {
    if (epochMs == null) return "never"
    val diff = System.currentTimeMillis() - epochMs
    val future = epochMs > System.currentTimeMillis()
    val minute = 60_000L
    val hour = 60L * minute
    val day = 24L * hour
    val abs = kotlin.math.abs(diff)
    val rel = when {
        abs < minute -> "now"
        abs < hour -> "${abs / minute}m"
        abs < day -> "${abs / hour}h"
        abs < 7L * day -> "${abs / day}d"
        else -> return SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(epochMs))
    }
    return if (future) "in $rel" else "$rel ago"
}

private fun statusColor(status: CronJobStatus): Color = when (status) {
    CronJobStatus.ACTIVE -> Color(0xFF4CAF50)
    CronJobStatus.PAUSED -> Color(0xFF888888)
    CronJobStatus.OFF -> Color(0xFF555555)
    CronJobStatus.ERROR -> Color(0xFFFF6B6B)
    CronJobStatus.NEEDS_ATTENTION -> Color(0xFFFFA726)
}

private fun statusLabel(status: CronJobStatus): String = when (status) {
    CronJobStatus.ACTIVE -> "Active"
    CronJobStatus.PAUSED -> "Paused"
    CronJobStatus.OFF -> "Off"
    CronJobStatus.ERROR -> "Error"
    CronJobStatus.NEEDS_ATTENTION -> "Needs attention"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(
    apiClient: HermesApiClient,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    var jobs by remember { mutableStateOf<List<CronJob>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    var expandedJobId by remember { mutableStateOf<String?>(null) }
    // job_id -> идёт ли сейчас действие (run/pause/resume), чтобы не спамить тапы
    val pendingActions = remember { mutableStateMapOf<String, Boolean>() }
    val listState = rememberLazyListState()

    fun loadJobs() {
        if (isLoading) return
        scope.launch {
            isLoading = true
            errorMessage = ""
            try {
                jobs = apiClient.listCronJobs()
            } catch (e: Exception) {
                errorMessage = e.message ?: "Failed to load tasks"
            }
            isLoading = false
        }
    }

    fun runAction(jobId: String, action: suspend (String) -> Unit) {
        if (pendingActions[jobId] == true) return
        scope.launch {
            pendingActions[jobId] = true
            try {
                action(jobId)
            } catch (_: Exception) {
                // Тихо игнорируем — статус всё равно перечитается из списка
            }
            loadJobs()
            pendingActions[jobId] = false
        }
    }

    LaunchedEffect(Unit) { loadJobs() }
    BackHandler(onBack = onBack)

    PullDownRefresh(
        isRefreshing = isLoading,
        onRefresh = { loadJobs() },
        canPullDown = { !listState.canScrollBackward },
        modifier = Modifier.fillMaxSize(),
    ) {
        Scaffold(
            containerColor = Color.Black,
            topBar = {
                TopAppBar(
                    title = {
                        Text("Tasks", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = Color.White)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black),
                    modifier = Modifier.statusBarsPadding()
                )
            }
        ) { innerPadding ->
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                when {
                    isLoading && jobs.isEmpty() && errorMessage.isBlank() -> {
                        CircularProgressIndicator(
                            color = Color(0xFFFFD700),
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                    errorMessage.isNotBlank() && jobs.isEmpty() -> {
                        Text(
                            text = errorMessage,
                            color = Color(0xFFFF6B6B),
                            fontSize = 14.sp,
                            modifier = Modifier.align(Alignment.Center).padding(32.dp)
                        )
                    }
                    jobs.isEmpty() -> {
                        Text(
                            text = "No tasks yet",
                            color = Color(0xFF888888),
                            fontSize = 15.sp,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                    else -> {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            if (errorMessage.isNotBlank()) {
                                item {
                                    Text(errorMessage, color = Color(0xFFFF6B6B), fontSize = 13.sp)
                                }
                            }
                            items(jobs, key = { it.id }) { job ->
                                CronJobCard(
                                    job = job,
                                    isExpanded = expandedJobId == job.id,
                                    isPending = pendingActions[job.id] == true,
                                    onToggleExpanded = {
                                        expandedJobId = if (expandedJobId == job.id) null else job.id
                                    },
                                    onRun = { runAction(job.id) { apiClient.runCronJob(it) } },
                                    onPause = { runAction(job.id) { apiClient.pauseCronJob(it) } },
                                    onResume = { runAction(job.id) { apiClient.resumeCronJob(it) } },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CronJobCard(
    job: CronJob,
    isExpanded: Boolean,
    isPending: Boolean,
    onToggleExpanded: () -> Unit,
    onRun: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
) {
    Surface(
        color = Color(0xFF1E1E1E),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().clickable { onToggleExpanded() }
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(statusColor(job.status))
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = job.name ?: job.scheduleDisplay ?: "(unnamed task)",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = statusLabel(job.status),
                    color = statusColor(job.status),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            job.scheduleDisplay?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Schedule, contentDescription = null, tint = Color(0xFF888888), modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(it, color = Color(0xFF999999), fontSize = 13.sp)
                }
                Spacer(modifier = Modifier.height(4.dp))
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Next: ${formatJobTime(job.nextRunAt)}", color = Color(0xFF666666), fontSize = 12.sp)
                Text("Last: ${formatJobTime(job.lastRunAt)}", color = Color(0xFF666666), fontSize = 12.sp)
            }

            if (isExpanded) {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = Color(0xFF2C2C2C))
                Spacer(modifier = Modifier.height(12.dp))

                job.prompt?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        color = Color(0xFFBBBBBB),
                        fontSize = 13.sp,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }

                if (job.status == CronJobStatus.ERROR && !job.lastError.isNullOrBlank()) {
                    Text(job.lastError, color = Color(0xFFFF6B6B), fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TaskActionButton(text = "Run now", enabled = !isPending, onClick = onRun)
                    if (job.state == "paused") {
                        TaskActionButton(text = "Resume", enabled = !isPending, onClick = onResume)
                    } else {
                        TaskActionButton(text = "Pause", enabled = !isPending, onClick = onPause)
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskActionButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        color = Color(0xFF2C2C2C),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.clickable(enabled = enabled) { onClick() }
    ) {
        Text(
            text = text,
            color = if (enabled) Color.White else Color(0xFF666666),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}
