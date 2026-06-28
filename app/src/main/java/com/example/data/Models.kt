package com.example.data

import java.util.UUID
import org.json.JSONObject

sealed class LoginResult {
    data class Success(val message: String) : LoginResult()
    data class Failure(val message: String) : LoginResult()
}

data class AuthStatus(
    val authEnabled: Boolean,
    val loggedIn: Boolean,
)

data class MobileSession(
    val sessionId: String,
    val title: String,
    val workspace: String?,
    val model: String?,
    val updatedAt: Long?,
    val messageCount: Int?,
)

enum class ChatBlockType { TEXT, THINKING, TOOL_USE, TOOL_RESULT, IMAGE }

data class ChatBlock(
    val type: ChatBlockType,
    val content: String,
    val toolName: String? = null,
    val imageUrl: String? = null,
    val mimeType: String? = null,
)

data class ChatMessageItem(
    val role: String,
    val blocks: List<ChatBlock>,
    val id: String = UUID.randomUUID().toString(),
)

sealed class SessionsResult {
    data class Success(val sessions: List<MobileSession>) : SessionsResult()
    data class Failure(val message: String) : SessionsResult()
}

sealed class NewSessionResult {
    data class Success(val session: MobileSession) : NewSessionResult()
    data class Failure(val message: String) : NewSessionResult()
}

data class WorkspaceEntry(
    val name: String,
    val path: String,
    val type: String,
    val size: Long?,
    val mtimeNs: Long?,
)

data class ModelInfo(
    val id: String,
    val label: String,
    val provider: String,
)

data class MemoryData(
    val memory: String,
    val user: String,
    val soul: String,
    val memoryMtime: Long?,
    val userMtime: Long?,
    val soulMtime: Long?,
)

data class ModelStats(
    val model: String,
    val sessions: Int,
    val inputTokens: Int,
    val outputTokens: Int,
    val totalTokens: Int,
    val cost: Double,
    val sessionShare: Int,
    val tokenShare: Int,
    val costShare: Int,
)

data class InsightsData(
    val periodDays: Int,
    val totalSessions: Int,
    val totalMessages: Int,
    val totalInputTokens: Int,
    val totalOutputTokens: Int,
    val totalTokens: Int,
    val totalCost: Double,
    val models: List<ModelStats>,
)
