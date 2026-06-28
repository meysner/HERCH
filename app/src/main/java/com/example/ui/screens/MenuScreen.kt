package com.example.ui.screens

import android.content.Context
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import com.example.SessionViewModel
import com.example.data.MobileSession
import com.example.ui.components.DrawerMenuItem
import com.example.ui.components.HerchLogo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun formatSessionTime(updatedAt: Long?): String {
    if (updatedAt == null) return ""
    val diff = System.currentTimeMillis() - updatedAt
    val minute = 60_000L
    val hour = 60L * minute
    val day = 24L * hour
    return when {
        diff < minute -> "now"
        diff < hour -> "${diff / minute}m"
        diff < day -> "${diff / hour}h"
        diff < 7L * day -> "${diff / day}d"
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(updatedAt))
    }
}

internal fun sessionMeta(session: MobileSession): String {
    val parts = mutableListOf<String>()
    session.messageCount?.let { count -> parts.add("$count messages") }
    session.workspace?.takeIf { it.isNotBlank() }?.let { workspace ->
        parts.add(workspace.substringAfterLast('\\').substringAfterLast('/'))
    }
    session.model?.takeIf { it.isNotBlank() }?.let { model ->
        parts.add(model.substringAfterLast('/'))
    }
    return parts.joinToString(" | ").ifBlank { session.sessionId }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun AppMenuScreen(
    viewModel: SessionViewModel,
    onNavigateToChat: (MobileSession) -> Unit,
    onNavigateToMemory: () -> Unit,
    onNavigateToStats: () -> Unit,
    onLogout: () -> Unit,
) {
    val sessions = viewModel.sessions
    val isLoading = viewModel.sessionsLoading
    val isCreating = viewModel.isCreatingSession
    val errorMessage = viewModel.sessionsError
    val hasInflight = viewModel.inflightSessions.isNotEmpty()

    var sessionToManage by remember { mutableStateOf<MobileSession?>(null) }
    var sessionToRename by remember { mutableStateOf<MobileSession?>(null) }
    var renameText by remember { mutableStateOf("") }
    var sessionToConfirmOpen by remember { mutableStateOf<MobileSession?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp),
            contentPadding = PaddingValues(top = 48.dp, bottom = 100.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    HerchLogo(
                        modifier     = Modifier.width(180.dp).height(50.dp),
                        isLoading    = isLoading,
                        isProcessing = hasInflight,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { viewModel.refreshSessions() },
                            enabled = !isLoading && !isCreating
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(22.dp),
                                    strokeWidth = 2.dp,
                                    color = Color.White
                                )
                            } else {
                                Icon(
                                    Icons.Outlined.Refresh,
                                    contentDescription = "Refresh",
                                    tint = Color.White,
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        IconButton(onClick = onLogout) {
                            Icon(
                                Icons.Outlined.Logout,
                                contentDescription = "Logout",
                                tint = Color.White,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(40.dp))
            }

            item { DrawerMenuItem(icon = Icons.Outlined.Psychology, text = "Memory", onClick = onNavigateToMemory) }
            item { DrawerMenuItem(icon = Icons.Outlined.BarChart, text = "Stats", onClick = onNavigateToStats) }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "Sessions", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                }
            }

            if (errorMessage.isNotBlank()) {
                item {
                    Text(
                        text = errorMessage,
                        color = Color(0xFFFF6B6B),
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                }
            }

            if (sessions.isEmpty() && !isLoading) {
                item {
                    Text(
                        text = "No sessions yet",
                        color = Color(0xFF888888),
                        fontSize = 15.sp,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                }
            }

            items(sessions, key = { it.sessionId }) { session ->
                val isActive = viewModel.inflightSessions.containsKey(session.sessionId)
                val isUnread = viewModel.unreadSessions[session.sessionId] == true
                val otherSessionStreaming = viewModel.inflightSessions.isNotEmpty() && !isActive

                val pulseAlpha by rememberInfiniteTransition(label = "pulse_${session.sessionId}")
                    .animateFloat(
                        initialValue = 1f,
                        targetValue = 0.25f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(650, easing = LinearEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "alpha"
                    )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .combinedClickable(
                            onClick = {
                                if (otherSessionStreaming) {
                                    onNavigateToChat(session)
                                } else {
                                    onNavigateToChat(session)
                                }
                            },
                            onLongClick = { sessionToManage = session }
                        )
                        .padding(vertical = 10.dp, horizontal = 4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isActive) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFFFD700).copy(alpha = pulseAlpha))
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        } else if (isUnread) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFFFD700))
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }

                        Text(
                            text = session.title,
                            color = Color.White,
                            fontSize = 15.sp,
                            maxLines = 2,
                            modifier = Modifier.weight(1f),
                            lineHeight = 20.sp,
                            fontWeight = if (isActive || isUnread) FontWeight.SemiBold else FontWeight.Medium
                        )
                        Text(
                            text = formatSessionTime(session.updatedAt),
                            color = if (isActive || isUnread) Color(0xFFFFD700) else Color(0xFF888888),
                            fontSize = 13.sp,
                            modifier = Modifier.padding(start = 16.dp)
                        )
                    }
                    Text(
                        text = when {
                            isActive -> "Request in progress..."
                            isUnread -> "New message"
                            else -> sessionMeta(session)
                        },
                        color = if (isActive || isUnread) Color(0xFFFFD700).copy(alpha = 0.75f) else Color(0xFF888888),
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }

        FloatingActionButton(
            onClick = {
                viewModel.createSession { session -> onNavigateToChat(session) }
            },
            containerColor = Color.White,
            contentColor = Color.Black,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 24.dp, bottom = 24.dp),
            shape = RoundedCornerShape(24.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 20.dp)
            ) {
                if (isCreating) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.Black)
                } else {
                    Icon(Icons.Outlined.Edit, contentDescription = "New chat", modifier = Modifier.size(20.dp))
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text("New chat", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }
    }

    // ── Long-press bottom sheet ───────────────────────────────────────────
    if (sessionToManage != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { sessionToManage = null },
            sheetState = sheetState,
            containerColor = Color(0xFF1E1E1E)
        ) {
            Column(modifier = Modifier.padding(bottom = 32.dp, top = 8.dp)) {
                Text(
                    text = sessionToManage?.title ?: "",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                )
                HorizontalDivider(color = Color(0xFF2C2C2C), modifier = Modifier.padding(bottom = 8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            renameText = sessionToManage?.title ?: ""
                            sessionToRename = sessionToManage
                            sessionToManage = null
                        }
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.Edit, contentDescription = null, tint = Color.White)
                    Spacer(modifier = Modifier.width(16.dp))
                    Text("Rename", color = Color.White, fontSize = 16.sp)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val s = sessionToManage
                            sessionToManage = null
                            if (s != null) viewModel.deleteSession(s.sessionId)
                        }
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.Delete, contentDescription = null, tint = Color(0xFFFF6B6B))
                    Spacer(modifier = Modifier.width(16.dp))
                    Text("Delete", color = Color(0xFFFF6B6B), fontSize = 16.sp)
                }
            }
        }
    }

    // ── Rename dialog ─────────────────────────────────────────────────────
    if (sessionToRename != null) {
        AlertDialog(
            onDismissRequest = { sessionToRename = null },
            containerColor = Color(0xFF1E1E1E),
            title = { Text("Rename chat", color = Color.White) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFFFFD700),
                        unfocusedBorderColor = Color.DarkGray,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val s = sessionToRename
                    val newName = renameText.trim()
                    sessionToRename = null
                    if (s != null && newName.isNotBlank()) {
                        viewModel.renameSession(s.sessionId, newName)
                    }
                }) { Text("Save", color = Color(0xFFFFD700)) }
            },
            dismissButton = {
                TextButton(onClick = { sessionToRename = null }) {
                    Text("Cancel", color = Color.Gray)
                }
            }
        )
    }
}