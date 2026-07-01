package com.example

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import com.example.data.ProfileInfo
import com.example.data.NewSessionResult
import com.example.data.SessionsResult
import com.example.domain.StreamEventHandler
import com.example.network.HermesApiClient
import com.example.ui.components.ReasoningLevel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.json.JSONObject

private fun List<ChatMessageItem>.withStableIds(sessionId: String): List<ChatMessageItem> =
    mapIndexed { index, msg -> msg.copy(id = "$sessionId:$index") }

private fun findModelById(models: List<ModelInfo>, modelId: String?): ModelInfo? {
    val wanted = modelId?.trim()?.takeIf { it.isNotBlank() } ?: return null
    return models.firstOrNull { model ->
        model.id == wanted ||
            model.id.substringAfterLast(':') == wanted ||
            model.id.substringAfter('@').substringAfter(':') == wanted
    }
}

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

    val profilesExpanded = mutableStateOf(prefs.getBoolean("profiles_expanded", true))
    val projectsExpanded = mutableStateOf(prefs.getBoolean("projects_expanded", false))

    // ── Проекты (фильтр списка сессий, по аналогии с веб-версией) ─────────
    var projects by mutableStateOf<List<com.example.data.ProjectInfo>>(emptyList())
        private set
    var projectsLoading by mutableStateOf(false)
        private set
    // null = показывать все сессии; иначе — project_id, по которому фильтруем
    var activeProjectFilter by mutableStateOf<String?>(null)
        private set

    val filteredSessions: List<MobileSession>
        get() = activeProjectFilter?.let { pid -> sessions.filter { it.projectId == pid } } ?: sessions

    fun selectProjectFilter(projectId: String?) {
        activeProjectFilter = projectId
    }

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
    // Сохранённый уровень reasoning per-session (как composerDrafts для текста)
    private val reasoningLevelMap = mutableMapOf<String, ReasoningLevel>()

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

    // ── Профили ───────────────────────────────────────────────────────────
    var profiles by mutableStateOf<List<ProfileInfo>>(emptyList())
        private set
    var activeProfileName by mutableStateOf("default")
        private set
    var profilesLoading by mutableStateOf(false)
        private set

    // ── Reasoning (поддержка зависит от модели) ───────────────────────────
    // true по умолчанию: показываем кнопку до первого ответа сервера,
    // чтобы не «моргать» при загрузке. false — явно не поддерживается.
    var supportsReasoning by mutableStateOf(true)
        private set
    var supportedReasoningEfforts by mutableStateOf<List<String>>(emptyList())
        private set

    private var pollJob: Job? = null

    // Сигнал "приложение сейчас на переднем плане", выставляется из
    // MainActivity.onStart()/onStop() (единственная Activity в приложении).
    // Пока false — цикл поллинга в startPolling() не делает сетевых запросов,
    // а спит на isForeground.first { it }, не потребляя ни сеть, ни CPU и не
    // мешая устройству уйти в Doze.
    private val _isForeground = MutableStateFlow(true)
    val isForeground: StateFlow<Boolean> = _isForeground

    fun setForeground(foreground: Boolean) {
        _isForeground.value = foreground
    }

    init {
        refreshSessions()
        loadProfiles()
        loadProjects()
        startPolling()

        viewModelScope.launch {
            snapshotFlow { profilesExpanded.value }
                .collect { prefs.edit().putBoolean("profiles_expanded", it).apply() }
        }
        viewModelScope.launch {
            snapshotFlow { projectsExpanded.value }
                .collect { prefs.edit().putBoolean("projects_expanded", it).apply() }
        }
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
            currentModel = findModelById(availableModels, session.model) ?: currentModel ?: availableModels.firstOrNull()
            fetchReasoningStatus(currentModel)
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

    fun loadProfiles() {
        if (profilesLoading) return
        viewModelScope.launch {
            profilesLoading = true
            val (list, active) = _apiClient.getProfiles()
            profiles = list
            activeProfileName = active
            profilesLoading = false
        }
    }

    fun loadProjects() {
        if (projectsLoading) return
        viewModelScope.launch {
            projectsLoading = true
            val list = _apiClient.getProjects()
            projects = list
            // Если активный фильтр указывал на проект, которого больше нет — сбрасываем.
            if (activeProjectFilter != null && list.none { it.projectId == activeProjectFilter }) {
                activeProjectFilter = null
            }
            projectsLoading = false
        }
    }

    fun switchProfile(name: String, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            _apiClient.switchProfile(name)
            // После переключения: обновляем профили, проекты (они per-profile), сессии и модели
            loadProfiles()
            activeProjectFilter = null
            loadProjects()
            refreshSessions()
            val freshModels = _apiClient.getModels()
            availableModels = freshModels
            currentModel = findModelById(freshModels, selectedSession?.model) ?: freshModels.firstOrNull()
            fetchReasoningStatus(currentModel)
            onDone()
        }
    }

    /**
     * Запрашивает у сервера поддержку reasoning для текущей модели.
     * Зеркало логики веб-фронтенда (fetchReasoningChip).
     * Вызывается при выборе модели и при выборе сессии.
     */
    // true пока идёт запрос fetchReasoningStatus — UI скрывает кнопку
    var reasoningStatusLoading by mutableStateOf(false)
        private set

    fun fetchReasoningStatus(model: ModelInfo? = currentModel) {
        viewModelScope.launch {
            // Сбрасываем список до ответа сервера: пока грузим — скрываем кнопку
            // (не показываем карусель из 4 уровней для модели, у которой может
            // быть 0 или 1 уровень, и не разрешаем клик с устаревшим уровнем).
            reasoningStatusLoading = true
            supportedReasoningEfforts = emptyList()

            val status = _apiClient.getReasoningStatus(model?.id, model?.provider)
            if (status != null) {
                supportsReasoning = status.first
                supportedReasoningEfforts = status.second
            } else {
                // Ошибка сети — fail-open: показываем кнопку, но без списка уровней
                // (fallback на полную карусель лучше, чем скрыть функцию совсем)
                supportsReasoning = true
            }
            reasoningStatusLoading = false
        }
    }

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

        val handler = buildHandler(sid, streamingBlocks, baseMessages)

        viewModelScope.launch {
            // Синхронизируем effort ДО показа UI-заглушки — если не удалось
            // установить уровень, стриминг не запускаем вовсе. Retry внутри
            // setReasoningEffort (3 попытки с backoff), поэтому здесь просто
            // проверяем результат.
            val effortResult = _apiClient.setReasoningEffort(reasoningLevel)
            if (effortResult.isFailure) {
                handler.onError(
                    "Не удалось установить уровень рассуждения: ${effortResult.exceptionOrNull()?.message}"
                )
                return@launch
            }

            // Показываем UI-заглушку только после успешного setReasoningEffort,
            // чтобы не оставлять «висячий» ассистентский пузырь при ошибке.
            messages = baseMessages + ChatMessageItem("assistant", streamingBlocks, id = "$sid:${userIdx + 1}")
            inflightSessions[sid] = SessionInflightState(
                blocks = streamingBlocks,
                baseMessages = baseMessages,
            )

            try {
                _apiClient.sendAndStream(
                    sessionId = sid,
                    message = text,
                    model = currentModel?.id,
                    workspace = currentWorkspace,
                    attachments = attachments,
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
            }.onFailure { onError(it.message ?: "Error adding") }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Черновики
    // ─────────────────────────────────────────────────────────────────────

    fun saveDraft(sessionId: String, text: String) { composerDrafts[sessionId] = text }
    fun getDraft(sessionId: String): String = composerDrafts[sessionId] ?: ""

    fun saveReasoningLevel(sessionId: String, level: ReasoningLevel) {
        reasoningLevelMap[sessionId] = level
    }
    fun getReasoningLevel(sessionId: String): ReasoningLevel =
        reasoningLevelMap[sessionId] ?: ReasoningLevel.NONE

    // ─────────────────────────────────────────────────────────────────────
    // Модель и воркспейс
    // ─────────────────────────────────────────────────────────────────────

    fun setModel(model: ModelInfo) {
        val oldModel = currentModel
        val oldSessionModel = selectedSession?.model
        currentModel = model
        // Сразу обновляем состояние reasoning для новой модели (как веб делает при смене модели)
        fetchReasoningStatus(model)
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

    fun resetModel() {
        val oldModel = currentModel
        val oldSessionModel = selectedSession?.model
        currentModel = availableModels.firstOrNull()
        fetchReasoningStatus(null)
        selectedSession?.sessionId?.let { sid ->
            selectedSession = selectedSession?.copy(model = null)
            viewModelScope.launch {
                try {
                    // Передаём пустую строку — сервер интерпретирует как "дефолт из конфига"
                    val updatedSession = _apiClient.updateSessionModel(sid, "", null)
                    selectedSession = updatedSession
                    currentModel = findModelById(availableModels, updatedSession.model) ?: availableModels.firstOrNull()
                    workspaceVersion++
                } catch (e: Exception) {
                    currentModel = oldModel
                    selectedSession = selectedSession?.copy(model = oldSessionModel)
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
                // Пока приложение в фоне -- не делаем сетевых запросов вообще,
                // просто спим на флаге до возврата на передний план. Как только
                // isForeground снова true, сразу опрашиваем (без ожидания
                // оставшихся секунд таймера), чтобы список сессий и бейджи
                // непрочитанного обновились мгновенно при возврате в приложение.
                _isForeground.first { it }
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
                delay(3_000L)
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