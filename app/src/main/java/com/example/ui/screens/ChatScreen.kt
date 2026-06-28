package com.example.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.SessionViewModel
import com.example.ui.components.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject

fun getFileMetadata(context: Context, uri: Uri): Pair<String, String> {
    var name = "file_${System.currentTimeMillis()}"
    val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex != -1) name = cursor.getString(nameIndex) ?: name
        }
    }
    return Pair(name, mimeType)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: SessionViewModel,
    onOpenDrawer: () -> Unit,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // Данные из ViewModel
    val session = viewModel.selectedSession
    val messages = viewModel.messages
    val isLoading = viewModel.messagesLoading
    val isStreaming = viewModel.isStreaming
    val errorMessage = viewModel.messagesError
    val pendingApproval = viewModel.pendingApproval
    val pendingClarify = viewModel.pendingClarify
    val availableModels = viewModel.availableModels
    val availableWorkspaces = viewModel.availableWorkspaces
    val currentModel = viewModel.currentModel
    val currentWorkspace = viewModel.currentWorkspace

    val baseUrl = remember { viewModel.getBaseUrl() }

    // Локальные состояния UI
    var composerText by remember { mutableStateOf("") }
    var attachmentsState by remember { mutableStateOf<List<AttachmentState>>(emptyList()) }
    var currentReasoningLevel by remember { mutableStateOf(ReasoningLevel.NONE) }
    var showModelSheet by remember { mutableStateOf(false) }
    var showWorkspaceSheet by remember { mutableStateOf(false) }
    var showWorkspaceBrowser by remember { mutableStateOf(false) }
    var showAddWorkspaceDialog by remember { mutableStateOf(false) }
    var newWorkspacePath by remember { mutableStateOf("") }
    var workspaceSuggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    var isAddingWorkspace by remember { mutableStateOf(false) }
    var addWorkspaceError by remember { mutableStateOf("") }
    var suggestJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var scrollToBottom by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val itemHeights = remember { mutableStateMapOf<String, Int>() }

    val copyAction: (String) -> Unit = remember {
        { text: String ->
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("message", text))
            scope.launch { snackbarHostState.showSnackbar("Скопировано") }
            Unit
        }
    }

    // Черновик: восстанавливаем при смене сессии
    LaunchedEffect(session?.sessionId) {
        attachmentsState = emptyList()
        session?.sessionId?.let { sid ->
            composerText = viewModel.getDraft(sid)
        }
    }

    // Сохраняем черновик при вводе
    LaunchedEffect(composerText) {
        session?.sessionId?.let { sid ->
            viewModel.saveDraft(sid, composerText)
        }
    }

    // При смене сессии: если новая сессия стримит — показываем её INFLIGHT из ViewModel.
    LaunchedEffect(session?.sessionId) {
        if (session?.sessionId != null && viewModel.isStreaming) {
            scrollToBottom = true
        }
    }

    // Уведомление
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    // Прокрутка вниз при первой загрузке сессии (один раз, без анимации).
    // Вызываем scrollToItem ДО ре-рендера LazyColumn: Compose ставит позицию в очередь
    // и применяет её при первом layout-проходе — скачка не будет.
    LaunchedEffect(session?.sessionId) {
        val msgCount = snapshotFlow { viewModel.messagesLoading to viewModel.messages.size }
            .first { (loading, size) -> !loading && size > 0 }
            .second
        listState.scrollToItem(msgCount - 1)
    }
    // Прокрутка вниз при стриминге — только если пользователь уже внизу
    val autoFollow by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = listState.layoutInfo.totalItemsCount
            total == 0 || last >= total - 2
        }
    }

    LaunchedEffect(messages.size) {
        if (!isStreaming && !scrollToBottom) return@LaunchedEffect
        if (autoFollow || scrollToBottom) {
            listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1)
            scrollToBottom = false
        }
    }

    // ── BackHandler ─────────────────────────────────────────────────────
    BackHandler {
        if (imeVisible) keyboardController?.hide()
        else onOpenDrawer()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = session?.title ?: "New chat",
                            fontSize = 17.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = session?.let { sessionMeta(it) } ?: "Choose or create a session",
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = Color(0xFFAAAAAA)
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                actions = {
                    IconButton(onClick = { showWorkspaceBrowser = true }) {
                        Icon(Icons.Outlined.FolderOpen, contentDescription = "Folder", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black),
                modifier = Modifier.statusBarsPadding()
            )
        },
        bottomBar = {
            Column {
                AnimatedVisibility(
                    visible = isStreaming,
                    enter = fadeIn(tween(200)) + expandVertically(tween(200)),
                    exit = fadeOut(tween(200)) + shrinkVertically(tween(200))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.Black)
                            .padding(horizontal = 20.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 1.5.dp,
                            color = Color(0xFFFFD700)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Hermes is thinking...", color = Color(0xFF888888), fontSize = 13.sp)
                    }
                }
                ChatBottomBar(
                    sessionId = session?.sessionId,
                    selectedModel = currentModel,
                    selectedWorkspace = currentWorkspace,
                    attachments = attachmentsState,
                    onModelClick = { showModelSheet = true },
                    onWorkspaceClick = { showWorkspaceSheet = true },
                    onAddUris = { uris ->
                        uris.forEach { uri ->
                            val sid = session?.sessionId ?: return@forEach
                            val (name, mime) = getFileMetadata(context, uri)
                            val att = AttachmentState(uri = uri, name = name, mimeType = mime)
                            attachmentsState = attachmentsState + att
                            scope.launch {
                                try {
                                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                                        ?: throw Exception("Cannot read file")
                                    val result = viewModel.uploadFile(sid, name, mime, bytes)
                                    attachmentsState = attachmentsState.map {
                                        if (it.id == att.id) it.copy(isUploading = false, uploadResult = result) else it
                                    }
                                } catch (e: Exception) {
                                    attachmentsState = attachmentsState.map {
                                        if (it.id == att.id) it.copy(isUploading = false, error = e.message ?: "Error") else it
                                    }
                                }
                            }
                        }
                    },
                    onRemoveAttachment = { attToRemove ->
                        attachmentsState = attachmentsState.filter { it.id != attToRemove.id }
                    },
                    composerText = composerText,
                    onComposerTextChange = { composerText = it },
                    onSend = { text ->
                        val uploadedJson = attachmentsState.mapNotNull { it.uploadResult }.map { json ->
                            JSONObject().apply {
                                put("name", json.optString("filename"))
                                put("path", json.optString("path"))
                                put("mime", json.optString("mime"))
                                put("size", json.optLong("size"))
                                put("is_image", json.optBoolean("is_image"))
                            }
                        }
                        attachmentsState = emptyList()
                        composerText = ""
                        // Очищаем черновик при отправке
                        session?.sessionId?.let { viewModel.saveDraft(it, "") }

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            val granted = ContextCompat.checkSelfPermission(
                                context, Manifest.permission.POST_NOTIFICATIONS
                            ) == PackageManager.PERMISSION_GRANTED
                            if (!granted) notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }

                        viewModel.sendMessage(
                            text = text,
                            attachments = uploadedJson,
                            reasoningLevel = currentReasoningLevel,
                        )
                        scrollToBottom = true
                    },
                    isStreaming = isStreaming,
                    onStop = {
                        viewModel.cancelCurrentStream()
                    },
                    isEnabled = pendingApproval == null && pendingClarify == null,
                    onReasoningLevelChange = { currentReasoningLevel = it }
                )
            }
        },
        containerColor = Color.Black
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            when {
                isLoading -> {
                    CircularProgressIndicator(
                        color = Color(0xFFFFD700),
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                session == null -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = null,
                            tint = Color(0xFFFFD700), modifier = Modifier.size(42.dp))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("No session selected", color = Color.White, fontSize = 18.sp)
                    }
                }
                messages.isEmpty() && errorMessage.isBlank() -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = null,
                            tint = Color(0xFFFFD700), modifier = Modifier.size(42.dp))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Send a message to start", color = Color(0xFF888888), fontSize = 16.sp)
                    }
                }
                else -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                        contentPadding = PaddingValues(top = 16.dp, bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        if (errorMessage.isNotBlank()) {
                            item {
                                Text(errorMessage, color = Color(0xFFFF6B6B), fontSize = 14.sp)
                            }
                        }
                        items(messages, key = { it.id }, contentType = { it.role }) { msg ->
                            HeightCachingItem(
                                cacheKey = msg.id,
                                heightCache = itemHeights,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                if (msg.role == "user") {
                                    UserMessageBubble(msg, baseUrl, copyAction)
                                } else {
                                    AssistantMessageBubble(msg, baseUrl)
                                }
                            }
                        }

                        item(key = "streaming_spacer") {
                            if (viewModel.isStreaming) {
                                Spacer(modifier = Modifier.height(120.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Sheets & Dialogs ────────────────────────────────────────────────
    if (showModelSheet) {
        ModelSelectionSheet(
            models = availableModels,
            selectedModel = currentModel,
            onModelSelected = { it?.let { m -> viewModel.setModel(m) } },
            onDismiss = { showModelSheet = false }
        )
    }

    if (showWorkspaceSheet) {
        SelectionBottomSheet(
            title = "Выберите workspace",
            items = availableWorkspaces,
            selectedItem = currentWorkspace,
            onItemSelected = { viewModel.setWorkspace(it) },
            onDismiss = { showWorkspaceSheet = false },
            formatItemName = { it.substringAfterLast('/').substringAfterLast('\\') },
            onAddWorkspace = {
                showWorkspaceSheet = false
                newWorkspacePath = ""
                workspaceSuggestions = emptyList()
                addWorkspaceError = ""
                showAddWorkspaceDialog = true
            }
        )
    }

    if (pendingApproval != null) {
        ApprovalBottomSheet(
            onRespond = { choice -> viewModel.respondToApproval(choice) },
            onDismiss = { /* ViewModel обработает */ }
        )
    }

    if (pendingClarify != null) {
        val choicesArr = pendingClarify?.optJSONArray("choices_offered")
        val choices = buildList {
            if (choicesArr != null) {
                for (i in 0 until choicesArr.length()) {
                    val c = choicesArr.optString(i)
                    if (c.isNotBlank()) add(c)
                }
            }
        }
        ClarifyBottomSheet(
            question = pendingClarify?.optString("question") ?: "Вопрос от агента",
            choices = choices,
            onRespond = { response -> viewModel.respondToClarify(response) },
            onDismiss = { /* ViewModel обработает */ }
        )
    }

    if (showWorkspaceBrowser && session?.sessionId != null) {
        WorkspaceBrowserSheet(
            sessionId = session.sessionId,
            viewModel = viewModel,
            onDismiss = { showWorkspaceBrowser = false },
            workspaceVersion = viewModel.workspaceVersion
        )
    }

    if (showAddWorkspaceDialog) {
        AlertDialog(
            onDismissRequest = { if (!isAddingWorkspace) showAddWorkspaceDialog = false },
            containerColor = Color(0xFF1E1E1E),
            title = { Text("Добавить workspace", color = Color.White) },
            text = {
                Column {
                    OutlinedTextField(
                        value = newWorkspacePath,
                        onValueChange = { new ->
                            newWorkspacePath = new
                            addWorkspaceError = ""
                            suggestJob?.cancel()
                            if (new.length >= 2) {
                                suggestJob = scope.launch {
                                    kotlinx.coroutines.delay(300)
                                    workspaceSuggestions = viewModel.suggestWorkspaces(new)
                                }
                            } else workspaceSuggestions = emptyList()
                        },
                        label = { Text("Путь к директории") },
                        placeholder = { Text("/home/user/project") },
                        singleLine = true,
                        isError = addWorkspaceError.isNotBlank(),
                        enabled = !isAddingWorkspace,
                        modifier = Modifier.fillMaxWidth(),
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
                    if (addWorkspaceError.isNotBlank()) {
                        Text(addWorkspaceError, color = Color(0xFFFF6B6B), fontSize = 13.sp,
                            modifier = Modifier.padding(top = 4.dp))
                    }
                    if (workspaceSuggestions.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Surface(
                            color = Color(0xFF2C2C2C), shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth().heightIn(max = 150.dp)
                        ) {
                            LazyColumn {
                                items(workspaceSuggestions) { suggestion ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth()
                                            .clickable {
                                                newWorkspacePath = suggestion
                                                workspaceSuggestions = emptyList()
                                            }
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Outlined.FolderOpen, contentDescription = null,
                                            tint = Color(0xFF888888), modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(suggestion, color = Color(0xFFCCCCCC), fontSize = 14.sp,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val path = newWorkspacePath.trim()
                        if (path.isBlank()) { addWorkspaceError = "Введите путь"; return@TextButton }
                        if (!path.startsWith("/") && !path.matches(Regex("^[A-Za-z]:\\\\.*"))) {
                            addWorkspaceError = "Путь должен начинаться с / или буквы диска"
                            return@TextButton
                        }
                        isAddingWorkspace = true
                        viewModel.addWorkspace(
                            path = path,
                            onSuccess = { showAddWorkspaceDialog = false; isAddingWorkspace = false },
                            onError = { error -> addWorkspaceError = error; isAddingWorkspace = false }
                        )
                    },
                    enabled = !isAddingWorkspace
                ) {
                    if (isAddingWorkspace) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color(0xFFFFD700))
                    } else {
                        Text("Добавить", color = Color(0xFFFFD700))
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { if (!isAddingWorkspace) showAddWorkspaceDialog = false },
                    enabled = !isAddingWorkspace
                ) {
                    Text("Отмена", color = Color.Gray)
                }
            }
        )
    }
}