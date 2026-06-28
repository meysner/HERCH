package com.example.ui.screens

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.MemoryData
import com.example.network.HermesApiClient
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun formatMtime(mtime: Long?): String {
    if (mtime == null || mtime == 0L) return ""
    val diff = System.currentTimeMillis() - mtime
    val minute = 60_000L
    val hour = 60L * minute
    val day = 24L * hour
    return when {
        diff < minute -> "now"
        diff < hour -> "${diff / minute}m"
        diff < day -> "${diff / hour}h"
        diff < 7L * day -> "${diff / day}d"
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(mtime))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen(
    apiClient: HermesApiClient,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    var memoryData by remember { mutableStateOf<MemoryData?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }

    fun loadMemory() {
        scope.launch {
            isLoading = true
            errorMessage = ""
            try {
                memoryData = apiClient.getMemory()
            } catch (e: Exception) {
                errorMessage = e.message ?: "Failed to load"
            }
            isLoading = false
        }
    }

    LaunchedEffect(Unit) { loadMemory() }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = {
                    Text("Memory", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                actions = {
                    IconButton(onClick = { loadMemory() }, enabled = !isLoading) {
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
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when {
                isLoading && memoryData == null -> {
                    CircularProgressIndicator(
                        color = Color(0xFFFFD700),
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                errorMessage.isNotBlank() && memoryData == null -> {
                    Text(
                        text = errorMessage,
                        color = Color(0xFFFF6B6B),
                        fontSize = 14.sp,
                        modifier = Modifier.align(Alignment.Center).padding(32.dp)
                    )
                }
                else -> {
                    val data = memoryData
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        if (errorMessage.isNotBlank()) {
                            Text(
                                text = errorMessage,
                                color = Color(0xFFFF6B6B),
                                fontSize = 13.sp,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                        }

                        MemorySection(
                            title = "My notes",
                            icon = Icons.Outlined.StickyNote2,
                            content = data?.memory ?: "",
                            mtime = data?.memoryMtime,
                            sectionKey = "memory",
                            apiClient = apiClient,
                            onSaved = { loadMemory() }
                        )

                        MemorySection(
                            title = "User profile",
                            icon = Icons.Outlined.PersonOutline,
                            content = data?.user ?: "",
                            mtime = data?.userMtime,
                            sectionKey = "user",
                            apiClient = apiClient,
                            onSaved = { loadMemory() }
                        )

                        MemorySection(
                            title = "Agent's soul",
                            icon = Icons.Outlined.Psychology,
                            content = data?.soul ?: "",
                            mtime = data?.soulMtime,
                            sectionKey = "soul",
                            apiClient = apiClient,
                            onSaved = { loadMemory() }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MemorySection(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: String,
    mtime: Long?,
    sectionKey: String,
    apiClient: HermesApiClient,
    onSaved: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var editText by remember { mutableStateOf("") }
    var isSaving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf("") }

    val previewText = content.take(150).replace('\n', ' ').trim()
    val isEmpty = content.isBlank()

    Surface(
        color = Color(0xFF1E1E1E),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (!editing) expanded = !expanded
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(icon, contentDescription = null, tint = Color(0xFFFFD700), modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    val mtimeText = formatMtime(mtime)
                    if (mtimeText.isNotBlank()) {
                        Text("Changed: $mtimeText", color = Color(0xFF666666), fontSize = 12.sp)
                    }
                }
                if (!editing) {
                    Icon(
                        if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = null,
                        tint = Color(0xFF666666)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = Color(0xFF2C2C2C))
            Spacer(modifier = Modifier.height(12.dp))

            if (!expanded) {
                if (isEmpty) {
                    Text("Empty", color = Color(0xFF555555), fontSize = 14.sp, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                } else {
                    Text(
                        text = previewText,
                        color = Color(0xFFBBBBBB),
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        maxLines = 2
                    )
                }
            } else {
                if (editing) {
                    BasicTextField(
                        value = editText,
                        onValueChange = { editText = it },
                        textStyle = TextStyle(color = Color.White, fontSize = 14.sp, lineHeight = 20.sp),
                        cursorBrush = SolidColor(Color(0xFFFFD700)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp, max = 400.dp)
                            .verticalScroll(rememberScrollState()),
                        decorationBox = { innerTextField ->
                            Box(modifier = Modifier.fillMaxWidth()) {
                                if (editText.isEmpty()) {
                                    Text("Enter text...", color = Color(0xFF555555), fontSize = 14.sp)
                                }
                                innerTextField()
                            }
                        }
                    )

                    if (saveError.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(saveError, color = Color(0xFFFF6B6B), fontSize = 13.sp)
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = {
                                editing = false
                                saveError = ""
                            },
                            enabled = !isSaving
                        ) {
                            Text("Cancel", color = Color.Gray)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                scope.launch {
                                    isSaving = true
                                    saveError = ""
                                    try {
                                        apiClient.writeMemory(sectionKey, editText)
                                        editing = false
                                        onSaved()
                                    } catch (e: Exception) {
                                        saveError = e.message ?: "Failed to save"
                                    }
                                    isSaving = false
                                }
                            },
                            enabled = !isSaving,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFD700), contentColor = Color.Black),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)
                        ) {
                            if (isSaving) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.Black)
                            } else {
                                Text("Save", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                } else {
                    if (isEmpty) {
                        Text(
                            "Empty",
                            color = Color(0xFF555555),
                            fontSize = 14.sp,
                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                        )
                    } else {
                        Text(content, color = Color(0xFFCCCCCC), fontSize = 14.sp, lineHeight = 22.sp)
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            onClick = {
                                editText = content
                                editing = true
                                saveError = ""
                            }
                        ) {
                            Icon(Icons.Outlined.Edit, contentDescription = null, tint = Color(0xFFFFD700), modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Edit", color = Color(0xFFFFD700), fontSize = 14.sp)
                        }
                    }
                }
            }
        }
    }
}
