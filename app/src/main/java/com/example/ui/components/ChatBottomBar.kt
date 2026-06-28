package com.example.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Square
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class AttachmentState(
    val id: String = java.util.UUID.randomUUID().toString(),
    val uri: Uri,
    val name: String,
    val mimeType: String,
    val isUploading: Boolean = true,
    val uploadResult: org.json.JSONObject? = null,
    val error: String? = null,
)

/**
 * ChatBottomBar — поле ввода с управляемым composerText.
 *
 * ВАЖНО: composerText и onComposerTextChange вынесены наружу, чтобы
 * ChatScreen/ViewModel мог сохранять черновик per-session и восстанавливать
 * его при переключении между сессиями. Не храним inputText внутри этого компонента.
 */
@Composable
fun ChatBottomBar(
    sessionId: String?,
    selectedModel: com.example.data.ModelInfo?,
    selectedWorkspace: String?,
    attachments: List<AttachmentState> = emptyList(),
    onModelClick: () -> Unit,
    onWorkspaceClick: () -> Unit,
    onAddUris: (List<Uri>) -> Unit = {},
    onRemoveAttachment: (AttachmentState) -> Unit = {},
    // Управляемый текст — состоянием владеет ChatScreen
    composerText: String,
    onComposerTextChange: (String) -> Unit,
    onSend: (String) -> Unit,
    isStreaming: Boolean = false,
    onStop: () -> Unit = {},
    isEnabled: Boolean = true,
    onReasoningLevelChange: (ReasoningLevel) -> Unit = {},
) {
    var reasoningLevel by remember { mutableStateOf(ReasoningLevel.NONE) }
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    var showAttachMenu by remember { mutableStateOf(false) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris -> if (uris.isNotEmpty()) onAddUris(uris) }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris -> if (uris.isNotEmpty()) onAddUris(uris) }

    val context = LocalContext.current
    var cameraPhotoUri by remember { mutableStateOf<Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && cameraPhotoUri != null) {
            onAddUris(listOf(cameraPhotoUri!!))
            cameraPhotoUri = null
        }
    }

    val isUploadingAny = attachments.any { it.isUploading }
    val canSend = !isStreaming && !isUploadingAny && isEnabled &&
            (composerText.isNotBlank() || attachments.isNotEmpty())

    val nextLevel = { current: ReasoningLevel ->
        when (current) {
            ReasoningLevel.NONE -> ReasoningLevel.LOW
            ReasoningLevel.LOW -> ReasoningLevel.MEDIUM
            ReasoningLevel.MEDIUM -> ReasoningLevel.HIGH
            ReasoningLevel.HIGH -> ReasoningLevel.EXTRA_HIGH
            ReasoningLevel.EXTRA_HIGH -> ReasoningLevel.NONE
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black)
            .imePadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1E1E1E), RoundedCornerShape(24.dp))
                .padding(start = 14.dp, end = 10.dp, top = 14.dp, bottom = 10.dp)
        ) {
            if (attachments.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(attachments, key = { it.id }) { att ->
                        AttachmentChip(attachment = att, onRemove = { onRemoveAttachment(att) })
                    }
                }
            }

            BasicTextField(
                value = composerText,
                onValueChange = onComposerTextChange,
                enabled = isEnabled,
                textStyle = TextStyle(
                    color = Color.White,
                    fontSize = 17.sp,
                    lineHeight = 24.sp,
                ),
                cursorBrush = SolidColor(Color.White),
                maxLines = 4,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 4.dp),
                decorationBox = { innerTextField ->
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopStart) {
                        if (composerText.isEmpty()) {
                            Text(
                                text = "Ask anything... /commands",
                                color = Color(0xFF666666),
                                fontSize = 17.sp,
                            )
                        }
                        innerTextField()
                    }
                },
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // Левая панель: вложения, модель, reasoning
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    // Кнопка прикрепления
                    Box {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { showAttachMenu = true }
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Outlined.AttachFile,
                                contentDescription = "Attach",
                                tint = Color(0xFF888888),
                                modifier = Modifier.size(22.dp),
                            )
                        }
                        DropdownMenu(
                            expanded = showAttachMenu,
                            onDismissRequest = { showAttachMenu = false },
                            modifier = Modifier.background(Color(0xFF2C2C2C)),
                        ) {
                            DropdownMenuItem(
                                text = { Text("Camera", color = Color.White) },
                                leadingIcon = {
                                    Icon(Icons.Outlined.CameraAlt, null, tint = Color(0xFF888888))
                                },
                                onClick = {
                                    showAttachMenu = false
                                    try {
                                        val file = java.io.File(
                                            context.cacheDir,
                                            "camera_${System.currentTimeMillis()}.jpg"
                                        ).also {
                                            it.parentFile?.mkdirs()
                                            if (!it.exists()) it.createNewFile()
                                        }
                                        val uri = androidx.core.content.FileProvider.getUriForFile(
                                            context, "${context.packageName}.fileprovider", file
                                        )
                                        cameraPhotoUri = uri
                                        cameraLauncher.launch(uri)
                                    } catch (_: Exception) {}
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Photo", color = Color.White) },
                                leadingIcon = {
                                    Icon(Icons.Outlined.Image, null, tint = Color(0xFF888888))
                                },
                                onClick = {
                                    showAttachMenu = false
                                    imagePickerLauncher.launch("image/*")
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("File", color = Color.White) },
                                leadingIcon = {
                                    Icon(Icons.Outlined.InsertDriveFile, null, tint = Color(0xFF888888))
                                },
                                onClick = {
                                    showAttachMenu = false
                                    filePickerLauncher.launch("*/*")
                                },
                            )
                        }
                    }

                    // Выбор модели
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onModelClick() }
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                selectedModel?.label ?: "model",
                                color = Color(0xFF888888),
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 120.dp),
                            )
                            Icon(
                                Icons.Filled.KeyboardArrowDown,
                                contentDescription = null,
                                tint = Color(0xFF888888),
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }

                    // Reasoning
                    ReasoningButton(
                        level = reasoningLevel,
                        onClick = {
                            reasoningLevel = nextLevel(reasoningLevel)
                            onReasoningLevelChange(reasoningLevel)
                        },
                    )
                }

                // Кнопка отправки / стоп
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .background(
                            if (!isStreaming && !canSend) Color(0xFF444444) else Color.White,
                            CircleShape,
                        )
                        .clickable(enabled = isStreaming || canSend) {
                            if (isStreaming) {
                                onStop()
                            } else {
                                val text = composerText.trim()
                                onSend(text)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (isStreaming) {
                        Icon(
                            Icons.Filled.Square,
                            contentDescription = "Stop",
                            tint = Color.Black,
                            modifier = Modifier.size(16.dp),
                        )
                    } else {
                        Icon(
                            Icons.Outlined.ArrowUpward,
                            contentDescription = "Send",
                            tint = Color.Black,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Нижняя строка (workspace, profile) — скрывается при клавиатуре
        AnimatedVisibility(
            visible = !imeVisible,
            enter = fadeIn(tween(200)) + expandVertically(
                animationSpec = tween(250, easing = FastOutSlowInEasing),
                expandFrom = Alignment.Top,
            ),
            exit = fadeOut(tween(150)) + shrinkVertically(
                animationSpec = tween(200, easing = FastOutLinearInEasing),
                shrinkTowards = Alignment.Top,
            ),
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onWorkspaceClick() }
                            .padding(4.dp),
                    ) {
                        Icon(
                            Icons.Outlined.FolderOpen,
                            contentDescription = null,
                            tint = Color(0xFF888888),
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            selectedWorkspace
                                ?.substringAfterLast('/')
                                ?.substringAfterLast('\\')
                                ?: "workspace",
                            color = Color(0xFF888888),
                            fontSize = 13.sp,
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            Icons.Filled.KeyboardArrowDown,
                            contentDescription = null,
                            tint = Color(0xFF888888),
                            modifier = Modifier.size(16.dp),
                        )
                    }

                    Spacer(modifier = Modifier.width(24.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { }
                            .padding(4.dp),
                    ) {
                        Icon(
                            Icons.Outlined.RadioButtonChecked,
                            contentDescription = null,
                            tint = Color(0xFF888888),
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Profile", color = Color(0xFF888888), fontSize = 13.sp)
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            Icons.Filled.KeyboardArrowDown,
                            contentDescription = null,
                            tint = Color(0xFF888888),
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
fun AttachmentChip(attachment: AttachmentState, onRemove: () -> Unit) {
    Surface(
        color = Color(0xFF2C2C2C),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.height(48.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 12.dp, end = 6.dp),
        ) {
            when {
                attachment.isUploading -> CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = Color(0xFFFFD700),
                )
                attachment.error != null -> Icon(
                    Icons.Outlined.ErrorOutline,
                    contentDescription = "Error",
                    tint = Color(0xFFFF6B6B),
                    modifier = Modifier.size(20.dp),
                )
                else -> Icon(
                    if (attachment.mimeType.startsWith("image/")) Icons.Outlined.Image
                    else Icons.Outlined.InsertDriveFile,
                    contentDescription = "File",
                    tint = Color(0xFF888888),
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = attachment.name,
                color = if (attachment.error != null) Color(0xFFFF6B6B) else Color.White,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 120.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Remove",
                    tint = Color.Gray,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}