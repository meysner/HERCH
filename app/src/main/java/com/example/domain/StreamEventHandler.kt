package com.example.domain

import androidx.compose.runtime.snapshots.SnapshotStateList
import com.example.data.ChatBlock
import com.example.data.ChatBlockType
import org.json.JSONObject

class StreamEventHandler(
    val sessionId: String,
    val blocks: SnapshotStateList<ChatBlock>,
    val onActivity: () -> Unit = {},
    val onStreamStarted: (streamId: String) -> Unit,
    val onPendingApproval: (JSONObject) -> Unit,
    val onPendingClarify: (JSONObject) -> Unit,
    val onDone: () -> Unit,
    val onError: (String) -> Unit,
) {
    private val buffer = StringBuilder()
    private var currentType: ChatBlockType? = null
    private var lastUpdateMs = 0L

    private fun flushBuffer() {
        if (buffer.isEmpty() || currentType == null) return
        val type = currentType!!
        val content = buffer.toString()

        val last = blocks.lastOrNull()
        if (last?.type == type) {
            blocks[blocks.lastIndex] = last.copy(content = last.content + content)
        } else {
            blocks.add(ChatBlock(type = type, content = content))
        }
        buffer.clear()
        lastUpdateMs = System.currentTimeMillis()
    }

    private fun appendWithThrottle(type: ChatBlockType, text: String) {
        onActivity()
        if (currentType != type) {
            flushBuffer()
            currentType = type
        }
        buffer.append(text)

        if (System.currentTimeMillis() - lastUpdateMs > 100L) {
            flushBuffer()
        }
    }

    fun onToken(text: String) = appendWithThrottle(ChatBlockType.TEXT, text)

    fun onReasoning(text: String) = appendWithThrottle(ChatBlockType.THINKING, text)

    fun onToolStart(name: String, id: String, input: String) {
        flushBuffer()
        // Сопоставляем по id вызова, а не по имени/позиции: сервер шлёт растущий
        // input для ОДНОГО и того же вызова несколькими "tool"-событиями подряд
        // (нужно обновлять на месте), но два РАЗНЫХ вызова одного инструмента,
        // идущих друг за другом (параллельные tool calls), должны остаться
        // отдельными блоками, а не перезатирать друг друга.
        val idx = if (id.isNotBlank()) blocks.indexOfLast { it.type == ChatBlockType.TOOL_USE && it.toolCallId == id } else -1
        if (idx != -1) {
            blocks[idx] = blocks[idx].copy(content = input)
        } else {
            blocks.add(ChatBlock(ChatBlockType.TOOL_USE, input, toolName = name, toolCallId = id.ifBlank { null }))
        }
    }

    fun onToolComplete(id: String, result: String) {
        flushBuffer()
        // Тэгаем результат тем же id — UI сопоставляет TOOL_USE/TOOL_RESULT по
        // toolCallId, а не по соседству в списке (см. ChatMessageBlocks).
        blocks.add(ChatBlock(ChatBlockType.TOOL_RESULT, result, toolCallId = id.ifBlank { null }))
    }

    fun finish() {
        flushBuffer()
        onDone()
    }
}
