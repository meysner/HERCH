package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.ChatBlockType
import com.example.data.ChatMessageItem
import dev.jeziellago.compose.markdowntext.MarkdownText

@Composable
fun HeightCachingItem(
    cacheKey: String,
    heightCache: MutableMap<String, Int>,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val cachedPx = heightCache[cacheKey] ?: 0

    Box(
        modifier = modifier
            .then(
                if (cachedPx > 0) Modifier.heightIn(min = with(density) { cachedPx.toDp() })
                else Modifier
            )
            .onSizeChanged { size ->
                if (size.height > 0) heightCache[cacheKey] = size.height
            }
    ) {
        content()
    }
}

@Composable
fun ReasoningBlock(previewText: String, fullText: String = previewText) {
    var expanded by remember { mutableStateOf(false) }
    val rotationAngle = if (expanded) 180f else 0f

    Surface(
        color = Color(0xFF141414),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }
    ) {
        Column {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Outlined.Psychology,
                    contentDescription = "Reasoning",
                    tint = Color.LightGray,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Thinking",
                    color = Color.LightGray,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.width(8.dp))
                if (!expanded) {
                    Text(
                        text = previewText,
                        color = Color(0xFF888888),
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = "Expand",
                    tint = Color.LightGray,
                    modifier = Modifier.graphicsLayer { rotationZ = rotationAngle }
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(tween(250)) + expandVertically(tween(300, easing = FastOutSlowInEasing)),
                exit = fadeOut(tween(200)) + shrinkVertically(tween(250, easing = FastOutLinearInEasing))
            ) {
                Column {
                    HorizontalDivider(
                        color = Color(0xFF2A2A2A),
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    Text(
                        text = fullText,
                        color = Color(0xFF999999),
                        fontSize = 14.sp,
                        lineHeight = 22.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun UserMessageBubble(
    msg: ChatMessageItem,
    baseUrl: String,
    onCopy: (String) -> Unit,
) {
    val textContent = msg.blocks
        .filter { it.type == ChatBlockType.TEXT }
        .joinToString("\n") { it.content }

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box {
            Surface(
                color = Color(0xFF2C2C2C),
                shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
                modifier = Modifier.widthIn(max = 280.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp, 10.dp)) {
                    msg.blocks.forEach { block ->
                        when (block.type) {
                            ChatBlockType.TEXT -> MarkdownText(
                                markdown = block.content,
                                style = TextStyle(
                                    color = Color.White,
                                    fontSize = 16.sp,
                                    lineHeight = 24.sp
                                ),
                                isTextSelectable = true
                            )
                            ChatBlockType.IMAGE -> block.imageUrl?.let { url ->
                                val fullUrl = if (url.startsWith("/")) "$baseUrl$url" else url
                                AsyncImage(
                                    model = fullUrl,
                                    contentDescription = "Attached image",
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                        .clip(RoundedCornerShape(8.dp)),
                                    contentScale = ContentScale.FillWidth
                                )
                            }
                            else -> {}
                        }
                    }
                }
            }
            if (textContent.isNotBlank()) {
                IconButton(
                    onClick = { onCopy(textContent) },
                    modifier = Modifier.align(Alignment.BottomEnd).size(24.dp)
                ) {
                    Icon(
                        Icons.Outlined.ContentCopy,
                        contentDescription = "Copy",
                        tint = Color(0xFF888888),
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun AssistantMessageBubble(
    msg: ChatMessageItem,
    baseUrl: String,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        msg.blocks.forEach { block ->
            when (block.type) {
                ChatBlockType.TEXT -> MarkdownText(
                    markdown = block.content,
                    style = TextStyle(
                        color = Color.White,
                        fontSize = 16.sp,
                        lineHeight = 24.sp
                    ),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    isTextSelectable = true
                )
                ChatBlockType.THINKING -> ReasoningBlock(
                    previewText = block.content.take(80).replace('\n', ' '),
                    fullText = block.content
                )
                ChatBlockType.TOOL_USE -> ToolCallBlock(
                    toolName = block.toolName ?: "tool",
                    input = block.content
                )
                ChatBlockType.TOOL_RESULT -> ToolResultBlock(result = block.content)
                ChatBlockType.IMAGE -> block.imageUrl?.let { url ->
                    val fullUrl = if (url.startsWith("/")) "$baseUrl$url" else url
                    AsyncImage(
                        model = fullUrl,
                        contentDescription = "Generated image",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 4.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.FillWidth
                    )
                }
            }
        }
    }
}

@Composable
fun ToolCallBlock(toolName: String, input: String) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        color = Color(0xFF0D1F0D),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }
    ) {
        Column(modifier = Modifier.padding(12.dp, 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Build,
                    contentDescription = null,
                    tint = Color(0xFF4CAF50),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    toolName,
                    color = Color(0xFF4CAF50),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                    tint = Color(0xFF666666),
                    modifier = Modifier.size(16.dp)
                )
            }
            if (expanded && input.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    input,
                    color = Color(0xFF888888),
                    fontSize = 13.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    lineHeight = 18.sp
                )
            }
        }
    }
}

@Composable
fun ToolResultBlock(result: String) {
    var expanded by remember { mutableStateOf(false) }
    val preview = result.take(120).replace('\n', ' ')
    Surface(
        color = Color(0xFF1A1A2E),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }
    ) {
        Column(modifier = Modifier.padding(12.dp, 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Output,
                    contentDescription = null,
                    tint = Color(0xFF888888),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (expanded) "Tool result" else preview,
                    color = Color(0xFF888888),
                    fontSize = 13.sp,
                    maxLines = if (expanded) Int.MAX_VALUE else 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                    tint = Color(0xFF666666),
                    modifier = Modifier.size(16.dp)
                )
            }
            if (expanded) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = result,
                    color = Color(0xFFAAAAAA),
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            }
        }
    }
}