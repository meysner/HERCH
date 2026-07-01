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
    val projectId: String? = null,
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

data class ProfileInfo(
    val name: String,
    val model: String?,
    val provider: String?,
    val isActive: Boolean,
    val isDefault: Boolean,
    val skillCount: Int,
    val gatewayRunning: Boolean,
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

data class ProjectInfo(
    val projectId: String,
    val name: String,
    val color: String?,
)

// ── Cron jobs ("Tasks") ─────────────────────────────────────────────────
enum class CronJobStatus { ACTIVE, PAUSED, OFF, ERROR, NEEDS_ATTENTION }

data class CronJob(
    val id: String,
    val name: String?,
    val scheduleDisplay: String?,
    val enabled: Boolean,
    val state: String?,
    val nextRunAt: Long?,
    val lastRunAt: Long?,
    val lastStatus: String?,
    val lastError: String?,
    val prompt: String?,
) {
    // Портирована логика из static/panels.js (_cronStatusMeta) — держим статус
    // в одном месте, чтобы веб и мобильный клиент показывали одинаковый смысл.
    val status: CronJobStatus
        get() = when {
            state == "paused" -> CronJobStatus.PAUSED
            !enabled -> CronJobStatus.OFF
            lastStatus == "error" -> CronJobStatus.ERROR
            else -> CronJobStatus.ACTIVE
        }
}