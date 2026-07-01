package com.example.ui.components

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.example.data.WorkspaceEntry
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val LARGE_FILE_THRESHOLD = 50L * 1024 * 1024

private fun formatFileSize(bytes: Long?): String {
    if (bytes == null) return ""
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        else -> String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }
}

private fun getFileIcon(entry: WorkspaceEntry): androidx.compose.ui.graphics.vector.ImageVector {
    if (entry.type == "dir") return Icons.Outlined.Folder
    val ext = entry.name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "jpg", "jpeg", "png", "gif", "webp", "svg", "bmp" -> Icons.Outlined.Image
        "mp4", "avi", "mkv", "mov", "webm" -> Icons.Outlined.VideoFile
        "mp3", "wav", "ogg", "flac", "aac" -> Icons.Outlined.AudioFile
        "zip", "tar", "gz", "7z", "rar" -> Icons.Outlined.FolderZip
        "pdf" -> Icons.Outlined.PictureAsPdf
        "txt", "md", "log", "csv" -> Icons.Outlined.TextSnippet
        "json", "xml", "yaml", "yml", "toml" -> Icons.Outlined.DataObject
        "py", "js", "ts", "kt", "java", "c", "cpp", "h", "rs", "go", "rb", "sh", "bat" -> Icons.Outlined.Code
        else -> Icons.Outlined.InsertDriveFile
    }
}

private fun getMimeType(fileName: String): String {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
}

private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")

// Раньше файлы в проводнике воркспейса всегда показывались общей иконкой,
// даже картинки — в отличие от типичного файлового браузера, где по .jpg/.png
// сразу видно превью. Используем тот же /api/file/raw эндпоинт, что и скачивание.
private fun imageThumbnailUrl(entry: WorkspaceEntry, baseUrl: String, sessionId: String): String? {
    if (entry.type == "dir") return null
    val ext = entry.name.substringAfterLast('.', "").lowercase()
    if (ext !in IMAGE_EXTENSIONS) return null
    val encodedPath = java.net.URLEncoder.encode(entry.path, "UTF-8")
    return "$baseUrl/api/file/raw?session_id=$sessionId&path=$encodedPath"
}

private fun parentPath(path: String): String {
    val normalized = path.trimEnd('/')
    val lastSlash = normalized.lastIndexOf('/')
    return if (lastSlash <= 0) "." else normalized.substring(0, lastSlash)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceBrowserSheet(
    sessionId: String,
    viewModel: com.example.SessionViewModel,
    onDismiss: () -> Unit,
    workspaceVersion: Int
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var currentPath by remember { mutableStateOf(".") }
    var entries by remember { mutableStateOf<List<WorkspaceEntry>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    var selectedFile by remember { mutableStateOf<WorkspaceEntry?>(null) }
    var showDeleteConfirm by remember { mutableStateOf<WorkspaceEntry?>(null) }
    var showLargeFileWarn by remember { mutableStateOf<WorkspaceEntry?>(null) }

    fun loadDir(path: String) {
        scope.launch {
            isLoading = true
            errorMessage = ""
            try {
                entries = viewModel.listWorkspaceFiles(sessionId, path)
                currentPath = path
            } catch (e: Exception) {
                errorMessage = e.message ?: "Failed to load directory"
            }
            isLoading = false
        }
    }

    fun refresh() { loadDir(currentPath) }

    fun openFile(entry: WorkspaceEntry) {
        scope.launch {
            try {
                val cacheDir = File(context.cacheDir, "workspace_files")
                cacheDir.mkdirs()
                val outFile = File(cacheDir, entry.name)
                outFile.outputStream().use { viewModel.downloadFileTo(sessionId, entry.path, it) }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", outFile)
                val mime = getMimeType(entry.name)
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mime)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    context.startActivity(intent)
                } catch (_: Exception) {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = mime
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(shareIntent, entry.name))
                }
            } catch (e: Exception) {
                errorMessage = e.message ?: "Failed to open file"
            }
        }
    }

    fun saveFile(entry: WorkspaceEntry) {
        scope.launch {
            try {
                val mime = getMimeType(entry.name)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val resolver = context.contentResolver
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, entry.name)
                        put(MediaStore.Downloads.MIME_TYPE, mime)
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    if (uri != null) {
                        resolver.openOutputStream(uri)?.use { viewModel.downloadFileTo(sessionId, entry.path, it) }
                    }
                } else {
                    val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    dir.mkdirs()
                    File(dir, entry.name).outputStream().use { viewModel.downloadFileTo(sessionId, entry.path, it) }
                }
                errorMessage = ""
            } catch (e: Exception) {
                errorMessage = e.message ?: "Failed to save file"
            }
        }
    }

    fun deleteFile(entry: WorkspaceEntry, recursive: Boolean) {
        scope.launch {
            try {
                viewModel.deleteWorkspaceFile(sessionId, entry.path, recursive)
                refresh()
            } catch (e: Exception) {
                errorMessage = e.message ?: "Failed to delete"
            }
        }
    }

    LaunchedEffect(workspaceVersion) { loadDir(".") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E1E1E),
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (currentPath != ".") {
                    IconButton(onClick = { loadDir(parentPath(currentPath)) }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                } else {
                    Spacer(modifier = Modifier.width(48.dp))
                }

                Text(
                    text = if (currentPath == ".") "Workspace" else currentPath.substringAfterLast('/'),
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                )

                IconButton(onClick = { refresh() }, enabled = !isLoading) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "Refresh", tint = Color.White)
                }

                IconButton(onClick = onDismiss) {
                    Icon(Icons.Outlined.Close, contentDescription = "Close", tint = Color.White)
                }
            }

            if (currentPath != ".") {
                val parts = currentPath.split("/")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = ".",
                        color = Color(0xFF888888),
                        fontSize = 13.sp,
                        modifier = Modifier
                            .clickable { loadDir(".") }
                            .padding(end = 4.dp)
                    )
                    parts.forEachIndexed { index, part ->
                        Text(" / ", color = Color(0xFF555555), fontSize = 13.sp)
                        val isLast = index == parts.lastIndex
                        Text(
                            text = part,
                            color = if (isLast) Color(0xFFFFD700) else Color(0xFF888888),
                            fontSize = 13.sp,
                            modifier = if (!isLast) {
                                Modifier.clickable {
                                    val target = parts.take(index + 1).joinToString("/")
                                    loadDir(target)
                                }
                            } else {
                                Modifier
                            }
                        )
                    }
                }
            }

            HorizontalDivider(color = Color(0xFF2C2C2C))

            Box(modifier = Modifier.weight(1f)) {
                when {
                    isLoading -> {
                        CircularProgressIndicator(
                            color = Color(0xFFFFD700),
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                    errorMessage.isNotBlank() -> {
                        Text(
                            text = errorMessage,
                            color = Color(0xFFFF6B6B),
                            fontSize = 14.sp,
                            modifier = Modifier.align(Alignment.Center).padding(32.dp)
                        )
                    }
                    entries.isEmpty() -> {
                        Text(
                            text = "Empty directory",
                            color = Color(0xFF888888),
                            fontSize = 15.sp,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                    else -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 32.dp)
                        ) {
                            val dirs = entries.filter { it.type == "dir" }.sortedBy { it.name.lowercase() }
                            val files = entries.filter { it.type != "dir" }.sortedBy { it.name.lowercase() }

                            items(dirs, key = { "d:${it.path}" }) { entry ->
                                FileRow(entry = entry, onClick = { loadDir(entry.path) })
                            }

                            if (dirs.isNotEmpty() && files.isNotEmpty()) {
                                item { Spacer(modifier = Modifier.height(8.dp)) }
                            }

                            items(files, key = { "f:${it.path}" }) { entry ->
                                FileRow(
                                    entry = entry,
                                    onClick = { selectedFile = entry },
                                    thumbnailUrl = imageThumbnailUrl(entry, viewModel.getBaseUrl(), sessionId)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (selectedFile != null) {
        val file = selectedFile!!
        val isLarge = (file.size ?: 0L) > LARGE_FILE_THRESHOLD
        FileActionSheet(
            entry = file,
            isLarge = isLarge,
            onOpen = {
                selectedFile = null
                if (isLarge) showLargeFileWarn = file else openFile(file)
            },
            onSave = { selectedFile = null; saveFile(file) },
            onDelete = { selectedFile = null; showDeleteConfirm = file },
            onDismiss = { selectedFile = null }
        )
    }

    if (showLargeFileWarn != null) {
        val file = showLargeFileWarn!!
        AlertDialog(
            onDismissRequest = { showLargeFileWarn = null },
            containerColor = Color(0xFF1E1E1E),
            title = { Text("Large file", color = Color.White) },
            text = {
                Text(
                    "File \"${file.name}\" is ${formatFileSize(file.size)}. Opening may be slow or fail. Continue?",
                    color = Color(0xFFCCCCCC)
                )
            },
            confirmButton = {
                TextButton(onClick = { showLargeFileWarn = null; openFile(file) }) {
                    Text("Open", color = Color(0xFFFFD700))
                }
            },
            dismissButton = {
                TextButton(onClick = { showLargeFileWarn = null }) {
                    Text("Cancel", color = Color.Gray)
                }
            }
        )
    }

    if (showDeleteConfirm != null) {
        val file = showDeleteConfirm!!
        val isDir = file.type == "dir"
        var recursive by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            containerColor = Color(0xFF1E1E1E),
            title = { Text("Delete ${if (isDir) "folder" else "file"}?", color = Color.White) },
            text = {
                Column {
                    Text(
                        "Are you sure you want to delete \"${file.name}\"?",
                        color = Color(0xFFCCCCCC)
                    )
                    if (isDir) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = recursive,
                                onCheckedChange = { recursive = it },
                                colors = CheckboxDefaults.colors(
                                    checkedColor = Color(0xFFFFD700),
                                    uncheckedColor = Color.Gray
                                )
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Delete recursively", color = Color.White, fontSize = 14.sp)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = null
                    deleteFile(file, recursive)
                }) {
                    Text("Delete", color = Color(0xFFFF6B6B))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = null }) {
                    Text("Cancel", color = Color.Gray)
                }
            }
        )
    }
}

@Composable
private fun FileRow(entry: WorkspaceEntry, onClick: () -> Unit, thumbnailUrl: String? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (thumbnailUrl != null) {
            AsyncImage(
                model = thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(22.dp)
                    .clip(RoundedCornerShape(4.dp))
            )
        } else {
            Icon(
                imageVector = getFileIcon(entry),
                contentDescription = null,
                tint = if (entry.type == "dir") Color(0xFFFFD700) else Color(0xFF888888),
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.name,
                color = Color.White,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (entry.type != "dir") {
                Text(
                    text = formatFileSize(entry.size),
                    color = Color(0xFF666666),
                    fontSize = 12.sp
                )
            }
        }

        if (entry.type == "dir") {
            Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = Color(0xFF555555),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileActionSheet(
    entry: WorkspaceEntry,
    isLarge: Boolean,
    onOpen: () -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF1E1E1E),
        dragHandle = { BottomSheetDefaults.DragHandle(color = Color.Gray) }
    ) {
        Column(modifier = Modifier.padding(bottom = 32.dp, top = 8.dp)) {
            Row(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = getFileIcon(entry),
                    contentDescription = null,
                    tint = Color(0xFF888888),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = entry.name,
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = formatFileSize(entry.size),
                        color = Color(0xFF888888),
                        fontSize = 13.sp
                    )
                }
            }

            HorizontalDivider(color = Color(0xFF2C2C2C), modifier = Modifier.padding(vertical = 8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpen() }
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.OpenInNew, contentDescription = null, tint = Color.White)
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text("Open", color = Color.White, fontSize = 16.sp)
                    if (isLarge) {
                        Text("Large file — may be slow", color = Color(0xFFFFD700), fontSize = 12.sp)
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSave() }
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.Download, contentDescription = null, tint = Color.White)
                Spacer(modifier = Modifier.width(16.dp))
                Text("Save to device", color = Color.White, fontSize = 16.sp)
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onDelete() }
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