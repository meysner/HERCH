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

// ── Attention (approval/clarify) ────────────────────────────────────────
// Зеркалит api/routes.py::_session_attention_summary(): сервер считает это
// независимо от того, открыт ли у клиента живой SSE-стрим на сессию (смотрит
// в глобальный _pending и clarify-очередь), поэтому доступно и в холодном
// опросе /api/sessions (WorkManager), и в живом состоянии стрима.
enum class SessionAttentionKind { APPROVAL, CLARIFY }

data class SessionAttention(
    val kind: SessionAttentionKind,
    val count: Int,
    val severity: String?,
)

data class MobileSession(
    val sessionId: String,
    val title: String,
    val workspace: String?,
    val model: String?,
    val updatedAt: Long?,
    val messageCount: Int?,
    val projectId: String? = null,
    val attention: SessionAttention? = null,
    val isStreaming: Boolean = false,
)

enum class ChatBlockType { TEXT, THINKING, TOOL_USE, TOOL_RESULT, IMAGE }

data class ChatBlock(
    val type: ChatBlockType,
    val content: String,
    val toolName: String? = null,
    val imageUrl: String? = null,
    val mimeType: String? = null,
    // ID вызова инструмента (tool_call_id/tool_use_id с сервера). Используется,
    // чтобы сопоставлять TOOL_USE с "своим" TOOL_RESULT по ID, а не по позиции
    // в списке — иначе параллельные/одноимённые вызовы инструментов путаются
    // или перезатирают друг друга (см. StreamEventHandler).
    val toolCallId: String? = null,
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

// Элемент ответа GET /api/crons/recent?since= — используется CronCheckWorker,
// чтобы не тащить полный список джоб и не диффать самому (сервер уже отдаёт
// только завершившиеся после `since`, см. api/routes.py::_handle_cron_recent).
data class CronCompletion(
    val jobId: String,
    val name: String?,
    val status: String?,
    val completedAtEpochSeconds: Double,
    val toastNotifications: Boolean,
    // Сессия, в которую вылилось выполнение джобы — если сервер её знает,
    // уведомление можно открыть сразу в чат, а не в список задач.
    val sessionId: String? = null,
)

// ── Cron jobs ("Tasks") ─────────────────────────────────────────────────
enum class CronJobStatus { ACTIVE, PAUSED, OFF, ERROR, NEEDS_ATTENTION, SCHEDULE_ERROR }

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
    // ── поля ниже нужны только для _isCronNeedsAttention/_isCronScheduleError,
    // сами по себе в UI не отображаются ─────────────────────────────────────
    val scheduleKind: String? = null,   // "cron" | "interval" | другое разовое
    val repeatUnlimited: Boolean = false, // repeat.times == null на сервере
    val toastNotifications: Boolean = true,
) {
    // Портирована логика из static/panels.js (_cronStatusMeta/_isCronNeedsAttention/
    // _isCronScheduleError) — держим статус в одном месте, чтобы веб и мобильный
    // клиент показывали одинаковый смысл.
    private val isRecurring: Boolean
        get() = scheduleKind == "cron" || scheduleKind == "interval"

    private val isNeedsAttention: Boolean
        get() = isRecurring && repeatUnlimited && !enabled && state == "completed" && nextRunAt == null

    private val isScheduleError: Boolean
        get() = isRecurring && nextRunAt == null && (state == "error" || lastStatus == "error")

    val status: CronJobStatus
        get() = when {
            isNeedsAttention -> CronJobStatus.NEEDS_ATTENTION
            isScheduleError -> CronJobStatus.SCHEDULE_ERROR
            state == "paused" -> CronJobStatus.PAUSED
            !enabled -> CronJobStatus.OFF
            lastStatus == "error" -> CronJobStatus.ERROR
            else -> CronJobStatus.ACTIVE
        }
}