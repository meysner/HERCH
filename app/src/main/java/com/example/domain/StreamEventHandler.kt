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

    fun onToolStart(name: String, input: String) {
        flushBuffer()
        val last = blocks.lastOrNull()
        if (last?.type == ChatBlockType.TOOL_USE && last.toolName == name) {
            blocks[blocks.lastIndex] = last.copy(content = input)
        } else {
            blocks.add(ChatBlock(ChatBlockType.TOOL_USE, input, toolName = name))
        }
    }

    fun onToolComplete(result: String) {
        flushBuffer()
        blocks.add(ChatBlock(ChatBlockType.TOOL_RESULT, result))
    }

    fun finish() {
        flushBuffer()
        onDone()
    }
}
