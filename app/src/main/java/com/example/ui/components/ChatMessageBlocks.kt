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
    // Если true — блок содержит раскрывающийся контент (tool/thinking).
    // В этом случае НЕ применяем heightIn(min=…), иначе после схлопывания
    // остаётся чёрное пустое пространство равное закэшированной высоте.
    isExpandable: Boolean = false,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val cachedPx = if (!isExpandable) (heightCache[cacheKey] ?: 0) else 0

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
                // Раньше здесь был Column(Modifier.padding(12.dp, 10.dp)) с
                // единым паддингом на ВСЕ блоки, включая IMAGE, у которой
                // сверху ещё был свой .clip(RoundedCornerShape(8.dp)) — радиус
                // меньше, чем у самого пузыря (18.dp). Из-за этого несовпадения
                // паддинг + два разных радиуса скругления серый фон Surface
                // просвечивал по краям картинки, особенно в верхних углах —
                // тот самый визуальный баг с серым фоном. Теперь паддинг
                // применяется только к текстовым блокам, а изображение идёт
                // "в край" пузыря без своего clip — Surface уже сам обрезает
                // содержимое по своей форме, так что углы совпадают идеально.
                Column {
                    msg.blocks.forEach { block ->
                        when (block.type) {
                            ChatBlockType.TEXT -> MarkdownText(
                                markdown = block.content,
                                style = TextStyle(
                                    color = Color.White,
                                    fontSize = 16.sp,
                                    lineHeight = 24.sp
                                ),
                                // Дефолтный фон инлайн-кода (`` `text` ``) у
                                // библиотеки MarkdownText — светло-серый,
                                // рассчитан на светлую тему. На нашей тёмной
                                // теме это давало плоские серые прямоугольники,
                                // визуально выпадающие из дизайна (виден на
                                // "control"/"option/win"/"command/alt" в
                                // ответах модели). Задаём цвета явно, в тон
                                // остальным тёмным поверхностям пузыря.
                                syntaxHighlightColor = Color(0xFF2C2C2C),
                                syntaxHighlightTextColor = Color(0xFFE0E0E0),
                                modifier = Modifier.padding(12.dp, 10.dp),
                                isTextSelectable = true
                            )
                            ChatBlockType.IMAGE -> block.imageUrl?.let { url ->
                                val fullUrl = if (url.startsWith("/")) "$baseUrl$url" else url
                                AsyncImage(
                                    model = fullUrl,
                                    contentDescription = "Attached image",
                                    modifier = Modifier.fillMaxWidth(),
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
        val blockList = msg.blocks

        // Сопоставляем TOOL_USE со "своим" TOOL_RESULT по toolCallId, а не по
        // соседству в списке — при параллельных вызовах инструментов результат
        // не обязательно оказывается сразу следующим блоком. Для старых данных
        // без toolCallId (например, уже закэшированная история) используем
        // адрес по соседству как fallback, сохраняя прежнее поведение.
        val resultIndexByCallId: Map<String, Int> = blockList.withIndex()
            .filter { it.value.type == ChatBlockType.TOOL_RESULT && it.value.toolCallId != null }
            .associate { it.value.toolCallId!! to it.index }
        val consumedResultIndices = mutableSetOf<Int>()

        var i = 0
        while (i < blockList.size) {
            val block = blockList[i]
            when (block.type) {
                ChatBlockType.TEXT -> {
                    MarkdownText(
                        markdown = block.content,
                        style = TextStyle(
                            color = Color.White,
                            fontSize = 16.sp,
                            lineHeight = 24.sp
                        ),
                        // См. комментарий в UserMessageBubble: дефолтный
                        // светло-серый фон инлайн-кода библиотеки не подходит
                        // под тёмную тему — задаём цвета явно.
                        syntaxHighlightColor = Color(0xFF2C2C2C),
                        syntaxHighlightTextColor = Color(0xFFE0E0E0),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                        isTextSelectable = true
                    )
                    i++
                }
                ChatBlockType.THINKING -> {
                    ReasoningBlock(
                        previewText = block.content.take(80).replace('\n', ' '),
                        fullText = block.content
                    )
                    i++
                }
                ChatBlockType.TOOL_USE -> {
                    val matchedIdx = block.toolCallId?.let { resultIndexByCallId[it] }
                    val next = blockList.getOrNull(i + 1)
                    when {
                        matchedIdx != null -> {
                            consumedResultIndices.add(matchedIdx)
                            ToolUseResultBlock(
                                toolName = block.toolName ?: "tool",
                                input = block.content,
                                result = blockList[matchedIdx].content
                            )
                            i++
                        }
                        // Fallback для блоков без id (старые/закэшированные данные)
                        block.toolCallId == null && next?.type == ChatBlockType.TOOL_RESULT && next.toolCallId == null -> {
                            ToolUseResultBlock(
                                toolName = block.toolName ?: "tool",
                                input = block.content,
                                result = next.content
                            )
                            i += 2
                        }
                        else -> {
                            // Результат ещё не пришёл (стриминг)
                            ToolCallBlock(
                                toolName = block.toolName ?: "tool",
                                input = block.content
                            )
                            i++
                        }
                    }
                }
                ChatBlockType.TOOL_RESULT -> {
                    // Уже показан вместе со своим TOOL_USE выше — не рисуем повторно.
                    if (i !in consumedResultIndices) {
                        ToolResultBlock(result = block.content)
                    }
                    i++
                }
                ChatBlockType.IMAGE -> {
                    block.imageUrl?.let { url ->
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
                    i++
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

// Объединённый блок: tool call + результат в одном surface через разделитель
@Composable
fun ToolUseResultBlock(toolName: String, input: String, result: String) {
    var expandedInput by remember { mutableStateOf(false) }
    var expandedResult by remember { mutableStateOf(false) }
    val preview = result.take(120).replace('\n', ' ')

    Surface(
        color = Color(0xFF0D1F0D),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            // ── Tool call header ──────────────────────────────────────────
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clickable { expandedInput = !expandedInput }
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
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
                    if (expandedInput) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                    tint = Color(0xFF666666),
                    modifier = Modifier.size(16.dp)
                )
            }
            AnimatedVisibility(
                visible = expandedInput && input.isNotBlank(),
                enter = fadeIn(tween(200)) + expandVertically(tween(250, easing = FastOutSlowInEasing)),
                exit = fadeOut(tween(150)) + shrinkVertically(tween(200, easing = FastOutLinearInEasing))
            ) {
                Text(
                    input,
                    color = Color(0xFF888888),
                    fontSize = 13.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp)
                )
            }

            // ── Разделитель ───────────────────────────────────────────────
            HorizontalDivider(color = Color(0xFF1E3A1E), thickness = 1.dp)

            // ── Result ────────────────────────────────────────────────────
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clickable { expandedResult = !expandedResult }
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Icon(
                    Icons.Outlined.Output,
                    contentDescription = null,
                    tint = Color(0xFF888888),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (expandedResult) "Result" else preview,
                    color = Color(0xFF888888),
                    fontSize = 13.sp,
                    maxLines = if (expandedResult) Int.MAX_VALUE else 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    if (expandedResult) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                    tint = Color(0xFF666666),
                    modifier = Modifier.size(16.dp)
                )
            }
            AnimatedVisibility(
                visible = expandedResult,
                enter = fadeIn(tween(200)) + expandVertically(tween(250, easing = FastOutSlowInEasing)),
                exit = fadeOut(tween(150)) + shrinkVertically(tween(200, easing = FastOutLinearInEasing))
            ) {
                Text(
                    text = result,
                    color = Color(0xFFAAAAAA),
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp)
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