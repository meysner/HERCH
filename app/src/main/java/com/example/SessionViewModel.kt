package com.example

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.ChatBlock
import com.example.data.ChatBlockType
import com.example.data.ChatMessageItem
import com.example.data.MobileSession
import com.example.data.ModelInfo
import com.example.data.NewSessionResult
import com.example.data.SessionsResult
import com.example.domain.StreamEventHandler
import com.example.network.HermesApiClient
import com.example.ui.components.ReasoningLevel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.json.JSONObject

private fun List<ChatMessageItem>.withStableIds(sessionId: String): List<ChatMessageItem> =
    mapIndexed { index, msg -> msg.copy(id = "$sessionId:$index") }

data class SessionInflightState(
    val blocks: SnapshotStateList<ChatBlock>,
    val baseMessages: List<ChatMessageItem>,
    val streamId: String? = null,
    var lastActivityMs: Long = System.currentTimeMillis(),
)

class SessionViewModel(
    private val prefs: SharedPreferences,
    // OkHttpClient инжектируется извне — единый пул + единый CookieJar
    private val httpClient: OkHttpClient,
) : ViewModel() {

    // apiClient теперь private — UI не должен обращаться к нему напрямую
    private val _apiClient = HermesApiClient(prefs, httpClient)

    // ── Список сессий ──────────────────────────────────────────────────────
    var sessions by mutableStateOf<List<MobileSession>>(emptyList())
        private set
    var sessionsLoading by mutableStateOf(false)
        private set
    var sessionsError by mutableStateOf("")
        private set
    var isCreatingSession by mutableStateOf(false)
        private set

    // ── Активная сессия ────────────────────────────────────────────────────
    var selectedSession by mutableStateOf<MobileSession?>(null)
        private set
    private var loadingSessionId: String? = null

    // ── Сообщения активной сессии ──────────────────────────────────────────
    var messages by mutableStateOf<List<ChatMessageItem>>(emptyList())
        private set
    var messagesLoading by mutableStateOf(false)
        private set
    var messagesError by mutableStateOf("")
        private set

    // ── INFLIGHT map: sessionId -> live стрим ─────────────────────────────
    val inflightSessions = mutableStateMapOf<String, SessionInflightState>()

    val unreadSessions = mutableStateMapOf<String, Boolean>()

    val isStreaming: Boolean
        get() = selectedSession?.sessionId?.let { inflightSessions.containsKey(it) } ?: false

    // ── Pending per-session ────────────────────────────────────────────────
    private val pendingApprovals = mutableMapOf<String, JSONObject?>()
    private val pendingClarifies = mutableMapOf<String, JSONObject?>()

    val pendingApproval: JSONObject?
        get() = selectedSession?.sessionId?.let { pendingApprovals[it] }
    val pendingClarify: JSONObject?
        get() = selectedSession?.sessionId?.let { pendingClarifies[it] }

    // ── Черновики ─────────────────────────────────────────────────────────
    private val composerDrafts = mutableMapOf<String, String>()

    // ── Модели / воркспейсы ───────────────────────────────────────────────
    var availableModels by mutableStateOf<List<ModelInfo>>(emptyList())
        private set
    var availableWorkspaces by mutableStateOf<List<String>>(emptyList())
        private set
    var currentModel by mutableStateOf<ModelInfo?>(null)
        private set
    var currentWorkspace by mutableStateOf<String?>(null)
        private set
    var workspaceVersion by mutableStateOf(0)
        private set

    private var pollJob: Job? = null

    init {
        refreshSessions()
        startPolling()
    }

    // ─────────────────────────────────────────────────────────────────────
    // Список сессий
    // ─────────────────────────────────────────────────────────────────────

    fun refreshSessions() {
        viewModelScope.launch {
            val cached = _apiClient.getCachedSessions()
            if (cached != null) sessions = cached
            sessionsLoading = true   // всегда — чтобы логотип запускал волну
            sessionsError = ""
            when (val result = _apiClient.listSessions()) {
                is SessionsResult.Success -> {
                    sessions = result.sessions
                    selectedSession?.sessionId?.let { sid ->
                        result.sessions.find { it.sessionId == sid }?.let { selectedSession = it }
                    }
                }
                is SessionsResult.Failure -> {
                    if (cached == null) sessionsError = result.message
                }
            }
            sessionsLoading = false
        }
    }

    fun createSession(onCreated: (MobileSession) -> Unit) {
        viewModelScope.launch {
            isCreatingSession = true
            when (val result = _apiClient.createSession()) {
                is NewSessionResult.Success -> {
                    sessions = listOf(result.session) + sessions
                    onCreated(result.session)
                }
                is NewSessionResult.Failure -> sessionsError = result.message
            }
            isCreatingSession = false
        }
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            _apiClient.deleteSession(sessionId)
            if (selectedSession?.sessionId == sessionId) {
                selectedSession = null
                messages = emptyList()
            }
            inflightSessions.remove(sessionId)
            pendingApprovals.remove(sessionId)
            pendingClarifies.remove(sessionId)
            refreshSessions()
        }
    }

    fun renameSession(sessionId: String, newTitle: String) {
        viewModelScope.launch {
            _apiClient.renameSession(sessionId, newTitle)
            refreshSessions()
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Открытие сессии
    // ─────────────────────────────────────────────────────────────────────

    fun openSession(session: MobileSession) {
        val sid = session.sessionId
        unreadSessions[sid] = false
        prefs.edit()
            .putBoolean("unread_$sid", false)
            .putInt("msg_count_$sid", session.messageCount ?: 0)
            .apply()
        if (loadingSessionId == sid) return

        val isSameSession = selectedSession?.sessionId == sid
        selectedSession = session
        messagesError = ""
        currentWorkspace = session.workspace

        val inflight = inflightSessions[sid]
        if (inflight != null) {
            if (!isSameSession) {
                messages = inflight.baseMessages + ChatMessageItem("assistant", inflight.blocks)
            }
            loadingSessionId = null
            return
        }

        if (!isSameSession) {
            messages = emptyList()
        }
        if (isSameSession && messages.isNotEmpty()) return

        loadingSessionId = sid
        viewModelScope.launch {
            messagesLoading = true
            val loaded = _apiClient.loadMessages(sid)
            if (loadingSessionId != sid) return@launch
            messagesLoading = false
            messages = loaded.withStableIds(sid)
            loadingSessionId = null
        }

        viewModelScope.launch {
            if (availableModels.isEmpty()) availableModels = _apiClient.getModels()
            if (availableWorkspaces.isEmpty()) availableWorkspaces = _apiClient.getWorkspaces()
            if (currentModel == null) {
                currentModel = session.model?.let { m -> availableModels.find { it.id == m } }
                    ?: availableModels.firstOrNull()
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Стриминг — общий построитель StreamEventHandler
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Строит StreamEventHandler для данной сессии.
     * Один экземпляр создаётся в sendMessage() и переиспользуется в reattachToService().
     */
    private fun buildHandler(
        sid: String,
        streamingBlocks: SnapshotStateList<ChatBlock>,
        baseMessages: List<ChatMessageItem>,
    ): StreamEventHandler = StreamEventHandler(
        sessionId = sid,
        blocks = streamingBlocks,
        onActivity = { inflightSessions[sid]?.lastActivityMs = System.currentTimeMillis() },
        onStreamStarted = { streamId ->
            inflightSessions[sid] = inflightSessions[sid]?.copy(streamId = streamId)
                ?: SessionInflightState(streamingBlocks, baseMessages, streamId)
        },
        onPendingApproval = { obj ->
            pendingApprovals[sid] = obj
        },
        onPendingClarify = { obj ->
            pendingClarifies[sid] = obj
        },
        onDone = {
            inflightSessions.remove(sid)
            pendingApprovals.remove(sid)
            pendingClarifies.remove(sid)
            if (selectedSession?.sessionId == sid) {
                val finalBlocks = streamingBlocks.toList()
                messages = messages.mapIndexed { i, m ->
                    if (i == messages.lastIndex && m.role == "assistant") m.copy(blocks = finalBlocks)
                    else m
                }
            }
            streamingBlocks.clear()
            refreshSessions()
        },
        onError = { error ->
            inflightSessions.remove(sid)
            pendingApprovals.remove(sid)
            pendingClarifies.remove(sid)
            if (selectedSession?.sessionId == sid) {
                messagesError = error
                if (streamingBlocks.isEmpty()) messages = messages.dropLast(1)
            }
        },
    )

    fun sendMessage(
        text: String,
        attachments: List<JSONObject> = emptyList(),
        reasoningLevel: ReasoningLevel = ReasoningLevel.NONE,
    ) {
        val session = selectedSession ?: return
        val sid = session.sessionId

        val streamingBlocks = mutableListOf<ChatBlock>().toMutableStateList()
        val userIdx = messages.size
        val userMsg = ChatMessageItem("user", listOf(ChatBlock(ChatBlockType.TEXT, text)), id = "$sid:$userIdx")
        val baseMessages = messages + userMsg

        messages = baseMessages + ChatMessageItem("assistant", streamingBlocks, id = "$sid:${userIdx + 1}")
        inflightSessions[sid] = SessionInflightState(
            blocks = streamingBlocks,
            baseMessages = baseMessages,
        )

        val handler = buildHandler(sid, streamingBlocks, baseMessages)

        viewModelScope.launch {
            if (reasoningLevel != ReasoningLevel.NONE) _apiClient.setReasoningEffort(reasoningLevel)
            try {
                _apiClient.sendAndStream(
                    sessionId = sid,
                    message = text,
                    model = currentModel?.id,
                    workspace = currentWorkspace,
                    attachments = attachments,
                    reasoningLevel = reasoningLevel,
                    onStreamStarted = { handler.onStreamStarted(it) },
                    onToken      = { handler.onToken(it) },
                    onReasoning  = { handler.onReasoning(it) },
                    onToolStart  = { name, _, input -> handler.onToolStart(name, input) },
                    onToolComplete = { _, result -> handler.onToolComplete(result) },
                    onApproval   = { handler.onPendingApproval(it) },
                    onClarify    = { handler.onPendingClarify(it) },
                    onDone       = { handler.finish() },
                    onError      = { handler.onError(it) },
                )
            } catch (_: Exception) {
                // Error already dispatched via onError callback
            }
        }
    }

    fun cancelCurrentStream() {
        val sid = selectedSession?.sessionId ?: return
        val inflight = inflightSessions[sid] ?: return
        val streamId = inflight.streamId ?: return

        viewModelScope.launch {
            runCatching { _apiClient.cancelStream(streamId) }
            inflightSessions.remove(sid)
            refreshSessions()
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Approval / Clarify
    // ─────────────────────────────────────────────────────────────────────

    fun respondToApproval(choice: String) {
        val sid = selectedSession?.sessionId ?: return
        viewModelScope.launch {
            _apiClient.respondToApproval(sid, choice)
            pendingApprovals.remove(sid)
        }
    }

    fun respondToClarify(response: String) {
        val sid = selectedSession?.sessionId ?: return
        viewModelScope.launch {
            _apiClient.respondToClarify(sid, response)
            pendingClarifies.remove(sid)
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Файлы и воркспейсы (делегируем в apiClient)
    // ─────────────────────────────────────────────────────────────────────

    suspend fun uploadFile(sessionId: String, fileName: String, mimeType: String, bytes: ByteArray) =
        _apiClient.uploadFile(sessionId, fileName, mimeType, bytes)

    suspend fun suggestWorkspaces(prefix: String) = _apiClient.suggestWorkspaces(prefix)

    fun getBaseUrl(): String = _apiClient.getBaseUrl()

    fun addWorkspace(path: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            runCatching {
                availableWorkspaces = _apiClient.addWorkspace(path)
                currentWorkspace = path
                onSuccess()
            }.onFailure { onError(it.message ?: "Ошибка добавления") }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Черновики
    // ─────────────────────────────────────────────────────────────────────

    fun saveDraft(sessionId: String, text: String) { composerDrafts[sessionId] = text }
    fun getDraft(sessionId: String): String = composerDrafts[sessionId] ?: ""

    // ─────────────────────────────────────────────────────────────────────
    // Модель и воркспейс
    // ─────────────────────────────────────────────────────────────────────

    fun setModel(model: ModelInfo) {
        val oldModel = currentModel
        val oldSessionModel = selectedSession?.model
        currentModel = model
        selectedSession?.sessionId?.let { sid ->
            selectedSession = selectedSession?.copy(model = model.id)
            viewModelScope.launch {
                try {
                    val updatedSession = _apiClient.updateSessionModel(sid, model.id, model.provider)
                    selectedSession = updatedSession
                    workspaceVersion++
                } catch (e: Exception) {
                    currentModel = oldModel
                    selectedSession = selectedSession?.copy(model = oldSessionModel)
                    println("Failed to update session model: ${e.message}")
                }
            }
        }
    }

    fun setWorkspace(workspace: String?) {
        val oldWorkspace = currentWorkspace
        val oldSessionWorkspace = selectedSession?.workspace
        currentWorkspace = workspace
        selectedSession?.sessionId?.let { sid ->
            selectedSession = selectedSession?.copy(workspace = workspace)
            viewModelScope.launch {
                try {
                    val updatedSession = _apiClient.updateSessionWorkspace(sid, workspace)
                    selectedSession = updatedSession
                    workspaceVersion++
                } catch (e: Exception) {
                    currentWorkspace = oldWorkspace
                    selectedSession = selectedSession?.copy(workspace = oldSessionWorkspace)
                    println("Failed to update session workspace: ${e.message}")
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Polling
    // ─────────────────────────────────────────────────────────────────────

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (true) {
                delay(3_000L)
                runCatching {
                    when (val result = _apiClient.listSessions()) {
                        is SessionsResult.Success -> {
                            val incomingSessions = result.sessions
                            val now = System.currentTimeMillis()
                            val editor = prefs.edit()

                            incomingSessions.forEach { s ->
                                val sid = s.sessionId
                                val newCount = s.messageCount ?: 0

                                val oldCount = prefs.getInt("msg_count_$sid", -1)
                                if (oldCount == -1) {
                                    editor.putInt("msg_count_$sid", newCount)
                                } else if (newCount > oldCount) {
                                    editor.putInt("msg_count_$sid", newCount)
                                    if (selectedSession?.sessionId != sid) {
                                        editor.putBoolean("unread_$sid", true)
                                        unreadSessions[sid] = true
                                    } else {
                                        val isStreamDead = !inflightSessions.containsKey(sid)
                                        if (isStreamDead) {
                                            viewModelScope.launch {
                                                val fresh = _apiClient.loadMessages(sid)
                                                if (fresh.isNotEmpty()) messages = fresh.withStableIds(sid)
                                            }
                                        }
                                    }
                                }

                                val inflight = inflightSessions[sid]
                                if (inflight != null && (now - inflight.lastActivityMs > 15_000L)) {
                                    inflightSessions.remove(sid)
                                    pendingApprovals.remove(sid)
                                    pendingClarifies.remove(sid)
                                }

                                if (!unreadSessions.containsKey(sid)) {
                                    unreadSessions[sid] = prefs.getBoolean("unread_$sid", false)
                                }
                            }
                            editor.apply()

                            sessions = incomingSessions
                            selectedSession?.sessionId?.let { sid ->
                                incomingSessions.find { it.sessionId == sid }
                                    ?.let { selectedSession = it }
                            }
                        }
                        is SessionsResult.Failure -> {}
                    }
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Файлы воркспейса (делегируем в apiClient)
    // ─────────────────────────────────────────────────────────────────────

    suspend fun listWorkspaceFiles(sessionId: String, path: String = ".") =
        _apiClient.listWorkspaceFiles(sessionId, path)

    suspend fun downloadFileTo(sessionId: String, path: String, out: java.io.OutputStream) =
        _apiClient.downloadFileTo(sessionId, path, out)

    suspend fun deleteWorkspaceFile(sessionId: String, path: String, recursive: Boolean = false) =
        _apiClient.deleteWorkspaceFile(sessionId, path, recursive)

        override fun onCleared() {
        super.onCleared()
        pollJob?.cancel()
    }

    class Factory(
        private val prefs: SharedPreferences,
        private val httpClient: OkHttpClient,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
            SessionViewModel(prefs, httpClient) as T
    }
}