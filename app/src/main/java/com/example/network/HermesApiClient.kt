package com.example.network

import android.content.SharedPreferences
import com.example.data.ChatBlock
import com.example.data.ChatBlockType
import com.example.data.ChatMessageItem
import com.example.data.MobileSession
import com.example.data.InsightsData
import com.example.data.MemoryData
import com.example.data.ModelInfo
import com.example.data.ProfileInfo
import com.example.data.ModelStats
import com.example.data.NewSessionResult
import com.example.data.SessionsResult
import com.example.data.WorkspaceEntry
import com.example.ui.components.ReasoningLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * @param prefs используется только для чтения webui_url.
 * @param httpClient общий клиент из AppModule (единый пул + единый CookieJar).
 */
class HermesApiClient(
    private val prefs: SharedPreferences,
    val httpClient: OkHttpClient,
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val client = httpClient
    val okHttpClient get() = client

    // Кэш baseUrl: читаем prefs только при реальном изменении
    @Volatile private var _cachedBaseUrl: String = ""

    private var cachedSessions: List<MobileSession>? = null
    private var sessionsCacheTime = 0L

    fun getBaseUrl(): String = savedBaseUrl()

    fun getCachedSessions(): List<MobileSession>? = cachedSessions

    suspend fun listSessions(): SessionsResult = withContext(Dispatchers.IO) {
        runCatching {
            val baseUrl = savedBaseUrl()
            val body = get("$baseUrl/api/sessions")
            val root = JSONObject(body)
            val array = root.optJSONArray("sessions") ?: JSONArray()
            val sessions = buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val session = item.toMobileSession()
                    if (session.sessionId.isNotBlank()) {
                        add(session)
                    }
                }
            }
            cachedSessions = sessions
            sessionsCacheTime = System.currentTimeMillis()
            SessionsResult.Success(sessions)
        }.getOrElse { SessionsResult.Failure(it.userMessage()) }
    }

    suspend fun createSession(): NewSessionResult = withContext(Dispatchers.IO) {
        runCatching {
            val baseUrl = savedBaseUrl()
            val request = Request.Builder()
                .url("$baseUrl/api/session/new")
                .post(JSONObject().toString().toRequestBody(jsonMediaType))
                .build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IOException(readError(response.code, body))
                }
                val session = JSONObject(body)
                    .optJSONObject("session")
                    ?.toMobileSession()
                    ?: throw IOException("Invalid session response.")
                if (session.sessionId.isBlank()) {
                    throw IOException("New session has no id.")
                }
                NewSessionResult.Success(session)
            }
        }.getOrElse { NewSessionResult.Failure(it.userMessage()) }
    }

    suspend fun getModels(): List<ModelInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val baseUrl = savedBaseUrl()
            val body = get("$baseUrl/api/models")
            val root = JSONObject(body)
            val groups = root.optJSONArray("groups") ?: JSONArray()
            val seen = linkedSetOf<String>()
            buildList {
                for (i in 0 until groups.length()) {
                    val group = groups.optJSONObject(i) ?: continue
                    val groupProvider = group.optString("provider", "")
                    val groupProviderId = group.optString("provider_id", "")
                    listOf("models", "extra_models").forEach { bucket ->
                        val models = group.optJSONArray(bucket) ?: return@forEach
                        for (j in 0 until models.length()) {
                            val model = models.optJSONObject(j) ?: continue
                            val id = model.optString("id").trim()
                            if (id.isNotBlank() && seen.add(id)) {
                                val label = model.optString("label").ifBlank { id.substringAfterLast('/') }
                                val provider = groupProviderId.ifBlank {
                                    groupProvider.ifBlank {
                                        when {
                                            id.startsWith("@") && id.contains(":") -> id.substringAfter("@").substringBefore(":")
                                            id.contains("/") -> id.substringBefore('/')
                                            else -> ""
                                        }
                                    }
                                }
                                add(ModelInfo(id = id, label = label, provider = provider))
                            }
                        }
                    }
                }
                val defaultModel = root.optString("default_model").trim()
                if (defaultModel.isNotBlank() && seen.add(defaultModel)) {
                    add(0, ModelInfo(
                        id = defaultModel,
                        label = defaultModel.substringAfterLast('/').substringAfterLast(':'),
                        provider = root.optString("active_provider").ifBlank { "" },
                    ))
                }
            }
        }.getOrDefault(emptyList())
    }

    suspend fun getWorkspaces(): List<String> = withContext(Dispatchers.IO) {
        runCatching {
            val baseUrl = savedBaseUrl()
            val body = get("$baseUrl/api/workspaces")
            val root = JSONObject(body)
            val array = root.optJSONArray("workspaces") ?: JSONArray()
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val path = item.optString("path")
                    if (path.isNotBlank()) add(path)
                }
            }
        }.getOrDefault(emptyList())
    }

    suspend fun addWorkspace(path: String, name: String? = null): List<String> = withContext(Dispatchers.IO) {
        val baseUrl = savedBaseUrl()
        val body = JSONObject().put("path", path)
        if (!name.isNullOrBlank()) body.put("name", name)
        val request = Request.Builder()
            .url("$baseUrl/api/workspaces/add")
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()
        client.newCall(request).execute().use { response ->
            val respBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException(readError(response.code, respBody))
        }
        getWorkspaces()
    }

    suspend fun suggestWorkspaces(prefix: String): List<String> = withContext(Dispatchers.IO) {
        runCatching {
            val baseUrl = savedBaseUrl()
            val encoded = java.net.URLEncoder.encode(prefix, "UTF-8")
            val body = get("$baseUrl/api/workspaces/suggest?prefix=$encoded")
            val root = JSONObject(body)
            val array = root.optJSONArray("suggestions") ?: JSONArray()
            buildList {
                for (i in 0 until array.length()) {
                    val s = array.optString(i)
                    if (s.isNotBlank()) add(s)
                }
            }
        }.getOrDefault(emptyList())
    }

    suspend fun respondToApproval(sessionId: String, choice: String) = withContext(Dispatchers.IO) {
        runCatching {
            val baseUrl = savedBaseUrl()
            val body = JSONObject().put("session_id", sessionId).put("choice", choice).toString()
            val request = Request.Builder()
                .url("$baseUrl/api/approval/respond")
                .post(body.toRequestBody(jsonMediaType))
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException(readError(response.code, response.body?.string()))
            }
        }
    }

    suspend fun respondToClarify(sessionId: String, responseText: String) = withContext(Dispatchers.IO) {
        runCatching {
            val baseUrl = savedBaseUrl()
            val body = JSONObject().put("session_id", sessionId).put("response", responseText).toString()
            val request = Request.Builder()
                .url("$baseUrl/api/clarify/respond")
                .post(body.toRequestBody(jsonMediaType))
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException(readError(response.code, response.body?.string()))
            }
        }
    }

    suspend fun loadMessages(sessionId: String): List<ChatMessageItem> = withContext(Dispatchers.IO) {
        runCatching {
            val baseUrl = savedBaseUrl()
            val body = get("$baseUrl/api/session?session_id=$sessionId")
            val root = JSONObject(body)
            val session = root.optJSONObject("session") ?: return@runCatching emptyList()
            val messages = session.optJSONArray("messages") ?: return@runCatching emptyList()

            val toolResults = mutableMapOf<String, String>()
            for (i in 0 until messages.length()) {
                val msg = messages.optJSONObject(i) ?: continue
                if (msg.optString("role") != "tool") continue
                val callId = msg.optString("tool_call_id").ifBlank { msg.optString("tool_use_id") }
                val content = msg.optString("content")
                if (callId.isNotBlank() && content.isNotBlank()) {
                    toolResults[callId] = content
                }
            }

            buildList {
                for (i in 0 until messages.length()) {
                    val msg = messages.optJSONObject(i) ?: continue
                    val role = msg.optString("role")
                    if (role != "user" && role != "assistant") continue

                    val blocks = mutableListOf<ChatBlock>()

                    val reasoning = msg.optString("reasoning").ifBlank {
                        msg.optString("reasoning_content")
                    }
                    if (reasoning.isNotBlank()) {
                        blocks.add(ChatBlock(ChatBlockType.THINKING, reasoning))
                    }

                    val contentArray = msg.optJSONArray("content")
                    if (contentArray != null) {
                        for (j in 0 until contentArray.length()) {
                            val block = contentArray.optJSONObject(j) ?: continue
                            when (block.optString("type")) {
                                "text" -> {
                                    val text = block.optString("text")
                                    if (text.isNotBlank()) blocks.add(ChatBlock(ChatBlockType.TEXT, text))
                                }
                                "thinking" -> {
                                    val text = block.optString("thinking")
                                    if (text.isNotBlank() && reasoning.isBlank()) {
                                        blocks.add(ChatBlock(ChatBlockType.THINKING, text))
                                    }
                                }
                                "tool_use" -> {
                                    val name = block.optString("name")
                                    val input = block.optJSONObject("input")?.toString() ?: ""
                                    val callId = block.optString("id")
                                    blocks.add(ChatBlock(ChatBlockType.TOOL_USE, input, toolName = name))
                                    toolResults[callId]?.let { result ->
                                        blocks.add(ChatBlock(ChatBlockType.TOOL_RESULT, result))
                                    }
                                }
                                "image_url" -> {
                                    val urlObj = block.optJSONObject("image_url")
                                    val url = urlObj?.optString("url") ?: ""
                                    if (url.isNotBlank()) {
                                        blocks.add(ChatBlock(ChatBlockType.IMAGE, content = "", imageUrl = url))
                                    }
                                }
                            }
                        }
                    } else {
                        val text = msg.optString("content")
                        if (text.isNotBlank()) blocks.add(ChatBlock(ChatBlockType.TEXT, text))
                    }

                    val toolCallsArr = msg.optJSONArray("tool_calls")
                    if (toolCallsArr != null) {
                        for (j in 0 until toolCallsArr.length()) {
                            val tc = toolCallsArr.optJSONObject(j) ?: continue
                            val fn = tc.optJSONObject("function")
                            val name = fn?.optString("name") ?: tc.optString("name")
                            val args = fn?.optString("arguments") ?: ""
                            val callId = tc.optString("id")
                            blocks.add(ChatBlock(ChatBlockType.TOOL_USE, args, toolName = name))
                            toolResults[callId]?.let { result ->
                                blocks.add(ChatBlock(ChatBlockType.TOOL_RESULT, result))
                            }
                        }
                    }

                    if (blocks.isNotEmpty()) add(ChatMessageItem(role, blocks))
                }
            }
        }.getOrDefault(emptyList())
    }

    suspend fun sendAndStream(
        sessionId: String,
        message: String,
        model: String? = null,
        workspace: String? = null,
        attachments: List<JSONObject> = emptyList(),
        onStreamStarted: (String) -> Unit,
        onToken: (String) -> Unit,
        onReasoning: (String) -> Unit,
        onToolStart: (name: String, id: String, input: String) -> Unit,
        onToolComplete: (id: String, result: String) -> Unit,
        onApproval: (JSONObject) -> Unit,
        onClarify: (JSONObject) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val baseUrl = savedBaseUrl()

        val startBody = JSONObject()
            .put("session_id", sessionId)
            .put("message", message)
        if (attachments.isNotEmpty()) {
            val arr = JSONArray()
            attachments.forEach { arr.put(it) }
            startBody.put("attachments", arr)
        }
        if (model != null) {
            startBody.put("model", model)
            val provider = if (model.contains('/')) model.substringBefore('/') else ""
            if (provider.isNotBlank()) {
                startBody.put("model_provider", provider)
            }
        }
        if (workspace != null) {
            startBody.put("workspace", workspace)
        }

        val startRequest = Request.Builder()
            .url("$baseUrl/api/chat/start")
            .post(startBody.toString().toRequestBody(jsonMediaType))
            .build()

        val streamId = try {
            client.newCall(startRequest).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw IOException(readError(response.code, body))
                JSONObject(body).optString("stream_id")
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { onError(e.message ?: "Failed to start stream") }
            return@withContext
        }

        if (streamId.isBlank()) {
            withContext(Dispatchers.Main) { onError("No stream_id received") }
            return@withContext
        }

        withContext(Dispatchers.Main) { onStreamStarted(streamId) }

        val sseClient = client.newBuilder()
            .readTimeout(5, TimeUnit.MINUTES)
            .build()

        val factory = EventSources.createFactory(sseClient)

        // Курсор реплея: последний увиденный SSE event_id вида "<stream_id>:<seq>"
        // (см. api/routes.py::_handle_sse_stream -> _sse_with_id). Используется,
        // если придётся переподключаться после обрыва соединения.
        var lastEventId = ""
        var lastSeq = 0L

        // Открывает одно SSE-соединение и приостанавливает корутину до его
        // штатного завершения (onClosed) либо обрыва (onFailure).
        // Возвращает null при штатном завершении или текст ошибки при обрыве.
        suspend fun connectOnce(url: String): String? = suspendCancellableCoroutine { continuation ->
            val sseRequest = Request.Builder()
                .url(url)
                .header("Accept", "text/event-stream")
                .build()

            val listener = object : EventSourceListener() {
                override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                    if (!id.isNullOrBlank()) {
                        lastEventId = id
                        id.substringAfterLast(':').toLongOrNull()?.let { seq ->
                            if (seq > lastSeq) lastSeq = seq
                        }
                    }

                    val eventType = type ?: "message"
                    if (data == "[DONE]") return

                    val json = runCatching { JSONObject(data) }.getOrNull() ?: return

                    kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch {
                        when (eventType) {
                            "token" -> json.optString("text").takeIf { it.isNotBlank() }?.let { onToken(it) }
                            "reasoning" -> json.optString("text").takeIf { it.isNotBlank() }?.let { onReasoning(it) }
                            "tool" -> onToolStart(
                                json.optString("name"),
                                json.optString("id").ifBlank { json.optString("tool_use_id") },
                                json.optString("preview").ifBlank { json.optString("arguments") }
                            )
                            "tool_complete" -> onToolComplete(
                                json.optString("tid").ifBlank { json.optString("id") },
                                json.optString("preview").ifBlank { json.optString("content").ifBlank { json.optString("result") } }
                            )
                            "approval" -> onApproval(json)
                            "clarify" -> onClarify(json)
                            "apperror" -> onError(json.optString("error", "Unknown error"))
                            "stream_end", "done", "cancel" -> eventSource.cancel()
                        }
                    }
                }

                override fun onClosed(eventSource: EventSource) {
                    if (continuation.isActive) continuation.resume(null)
                }

                override fun onFailure(eventSource: EventSource, t: Throwable?, response: okhttp3.Response?) {
                    if (continuation.isActive) {
                        continuation.resume(t?.message ?: "Stream connection lost")
                    }
                }
            }

            val eventSource = factory.newEventSource(sseRequest, listener)
            continuation.invokeOnCancellation { eventSource.cancel() }
        }

        val liveUrl = "$baseUrl/api/chat/stream?stream_id=$streamId"
        var failure = connectOnce(liveUrl)

        // Зеркалим логику веб-клиента (messages.js attachLiveStream/onerror):
        // ровно одна попытка реконнекта. Сначала спрашиваем у сервера статус
        // стрима -- если воркер ещё жив, просто продолжаем читать тот же
        // живой поток; если воркер уже завершился, реплеим хвост из журнала
        // запуска начиная с последнего увиденного event_id/seq, чтобы не
        // потерять и не задублировать события.
        if (failure != null) {
            delay(1500)
            val status = fetchStreamStatus(baseUrl, streamId)
            val reconnectUrl = when {
                status == null -> null
                status.optBoolean("active", false) -> liveUrl
                status.optBoolean("replay_available", false) -> {
                    val afterId = URLEncoder.encode(lastEventId, "UTF-8")
                    "$liveUrl&replay=1&after_seq=$lastSeq&after_event_id=$afterId"
                }
                else -> null
            }
            failure = if (reconnectUrl != null) connectOnce(reconnectUrl) else failure
        }

        withContext(Dispatchers.Main) {
            if (failure == null) onDone() else onError(failure)
        }
    }

    /** Опрашивает /api/chat/stream/status: жив ли воркер и доступен ли replay из журнала. */
    private suspend fun fetchStreamStatus(baseUrl: String, streamId: String): JSONObject? =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url("$baseUrl/api/chat/stream/status?stream_id=$streamId")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    JSONObject(response.body?.string().orEmpty())
                }
            }.getOrNull()
        }

    /**
     * Устанавливает уровень рассуждения на сервере.
     * Возвращает Result.success(Unit) при успехе, Result.failure при исчерпании попыток.
     *
     * Retry-политика: до 3 попыток с экспоненциальным backoff (200ms -> 400ms).
     * Только сетевые/IO ошибки повторяются; 4xx считается постоянной ошибкой.
     */
    suspend fun setReasoningEffort(level: ReasoningLevel): Result<Unit> = withContext(Dispatchers.IO) {
        val effort = when (level) {
            ReasoningLevel.NONE -> "none"
            ReasoningLevel.LOW -> "low"
            ReasoningLevel.MEDIUM -> "medium"
            ReasoningLevel.HIGH -> "high"
            ReasoningLevel.EXTRA_HIGH -> "xhigh"
        }
        val body = JSONObject().put("effort", effort).toString()
        val maxAttempts = 3
        var lastError: Throwable = IOException("Unknown error")
        repeat(maxAttempts) { attempt ->
            runCatching {
                val request = Request.Builder()
                    .url("${savedBaseUrl()}/api/reasoning")
                    .post(body.toRequestBody(jsonMediaType))
                    .build()
                client.newCall(request).execute().use { response ->
                    if (response.code in 400..499) {
                        // Клиентская ошибка — повторять бессмысленно
                        throw IOException("Server rejected reasoning effort ($effort): HTTP ${response.code}")
                    }
                    if (!response.isSuccessful) {
                        throw IOException("Failed to set reasoning effort: HTTP ${response.code}")
                    }
                }
            }.onSuccess {
                return@withContext Result.success(Unit)
            }.onFailure { e ->
                lastError = e
                // 4xx — не ретраим
                if (e is IOException && e.message?.contains("Server rejected") == true) {
                    return@withContext Result.failure(e)
                }
                if (attempt < maxAttempts - 1) {
                    delay(200L * (1 shl attempt)) // 200ms, 400ms
                }
            }
        }
        Result.failure(lastError)
    }

    /**
     * Проверяет, поддерживает ли модель reasoning.
     * Зеркало логики веб-фронтенда: GET /api/reasoning?model=X&provider=Y
     * Возвращает Pair(supportsReasoning, supportedEfforts) или null при ошибке сети.
     */
    suspend fun getReasoningStatus(
        modelId: String? = null,
        provider: String? = null,
    ): Pair<Boolean, List<String>>? = withContext(Dispatchers.IO) {
        runCatching {
            val baseUrl = savedBaseUrl()
            val params = buildString {
                if (!modelId.isNullOrBlank()) {
                    append("model=")
                    append(URLEncoder.encode(modelId, "UTF-8"))
                }
                if (!provider.isNullOrBlank()) {
                    if (isNotEmpty()) append("&")
                    append("provider=")
                    append(URLEncoder.encode(provider, "UTF-8"))
                }
            }
            val url = if (params.isNotEmpty()) "$baseUrl/api/reasoning?$params"
                      else "$baseUrl/api/reasoning"
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val json = JSONObject(response.body?.string().orEmpty())
                val supports = json.optBoolean("supports_reasoning_effort", false)
                val effortsArr = json.optJSONArray("supported_efforts")
                val efforts = if (effortsArr != null)
                    List(effortsArr.length()) { effortsArr.getString(it) }
                else emptyList()
                Pair(supports, efforts)
            }
        }.getOrNull()
    }

    // ── Profiles ─────────────────────────────────────────────────────────

    suspend fun getProfiles(): Pair<List<ProfileInfo>, String> = withContext(Dispatchers.IO) {
        runCatching {
            val baseUrl = savedBaseUrl()
            val req = Request.Builder().url("$baseUrl/api/profiles").get().build()
            client.newCall(req).execute().use { resp ->
                val body = JSONObject(resp.body?.string().orEmpty())
                val active = body.optString("active", "default")
                val arr = body.optJSONArray("profiles") ?: return@use Pair(emptyList(), active)
                val list = List(arr.length()) { i ->
                    val p = arr.getJSONObject(i)
                    ProfileInfo(
                        name = p.optString("name"),
                        model = p.optString("model").ifBlank { null },
                        provider = p.optString("provider").ifBlank { null },
                        isActive = p.optBoolean("is_active"),
                        isDefault = p.optBoolean("is_default"),
                        skillCount = p.optInt("skill_count", 0),
                        gatewayRunning = p.optBoolean("gateway_running"),
                    )
                }
                Pair(list, active)
            }
        }.getOrDefault(Pair(emptyList(), "default"))
    }

    suspend fun switchProfile(name: String) = withContext(Dispatchers.IO) {
        runCatching {
            val baseUrl = savedBaseUrl()
            val body = JSONObject().put("name", name).toString()
            val req = Request.Builder()
                .url("$baseUrl/api/profile/switch")
                .post(body.toRequestBody(jsonMediaType))
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("Switch failed: ${resp.code}")
            }
        }
    }

    suspend fun getProjects(): List<com.example.data.ProjectInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val baseUrl = savedBaseUrl()
            val body = get("$baseUrl/api/projects")
            val arr = JSONObject(body).optJSONArray("projects") ?: JSONArray()
            List(arr.length()) { i ->
                val p = arr.getJSONObject(i)
                com.example.data.ProjectInfo(
                    projectId = p.optString("project_id"),
                    name = p.optString("name").ifBlank { "Untitled" },
                    color = p.optString("color").ifBlank { null },
                )
            }.filter { it.projectId.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    suspend fun deleteSession(sessionId: String) = withContext(Dispatchers.IO) {
        runCatching {
            val baseUrl = savedBaseUrl()
            val body = JSONObject().put("session_id", sessionId).toString()
            val request = Request.Builder()
                .url("$baseUrl/api/session/delete")
                .post(body.toRequestBody(jsonMediaType))
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Failed to delete session")
            }
        }
    }

    suspend fun renameSession(sessionId: String, newTitle: String) = withContext(Dispatchers.IO) {
        runCatching {
            val baseUrl = savedBaseUrl()
            val body = JSONObject().put("session_id", sessionId).put("title", newTitle).toString()
            val request = Request.Builder()
                .url("$baseUrl/api/session/rename")
                .post(body.toRequestBody(jsonMediaType))
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Failed to rename session")
            }
        }
    }

    suspend fun cancelStream(streamId: String) = withContext(Dispatchers.IO) {
        runCatching {
            val baseUrl = savedBaseUrl()
            val request = Request.Builder()
                .url("$baseUrl/api/chat/cancel?stream_id=$streamId")
                .get()
                .build()
            client.newCall(request).execute().use { }
        }
    }

    suspend fun updateSessionWorkspace(sessionId: String, workspace: String?): MobileSession = withContext(Dispatchers.IO) {
        runCatching {
            val baseUrl = savedBaseUrl()
            val body = JSONObject().put("session_id", sessionId)
            workspace?.let { body.put("workspace", it) }
            val request = Request.Builder()
                .url("$baseUrl/api/session/update")
                .post(body.toString().toRequestBody(jsonMediaType))
                .build()
            client.newCall(request).execute().use { response ->
                val respBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw IOException(readError(response.code, respBody))
                val root = JSONObject(respBody)
                val session = root.optJSONObject("session")?.toMobileSession()
                    ?: throw IOException("Invalid session response")
                return@runCatching session
            }
        }.getOrElse { throw it }
    }

    suspend fun updateSessionModel(sessionId: String, modelId: String, modelProvider: String?): MobileSession = withContext(Dispatchers.IO) {
        runCatching {
            val baseUrl = savedBaseUrl()
            val body = JSONObject().put("session_id", sessionId).put("model", modelId)
            modelProvider?.let { body.put("model_provider", it) }
            val request = Request.Builder()
                .url("$baseUrl/api/session/update")
                .post(body.toString().toRequestBody(jsonMediaType))
                .build()
            client.newCall(request).execute().use { response ->
                val respBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw IOException(readError(response.code, respBody))
                val root = JSONObject(respBody)
                val session = root.optJSONObject("session")?.toMobileSession()
                    ?: throw IOException("Invalid session response")
                return@runCatching session
            }
        }.getOrElse { throw it }
    }

    suspend fun uploadFile(sessionId: String, fileName: String, mimeType: String, fileBytes: ByteArray): JSONObject = withContext(Dispatchers.IO) {
        runCatching {
            val baseUrl = savedBaseUrl()
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("session_id", sessionId)
                .addFormDataPart(
                    "file",
                    fileName,
                    fileBytes.toRequestBody((mimeType.ifBlank { "application/octet-stream" }).toMediaTypeOrNull())
                )
                .build()
            val request = Request.Builder()
                .url("$baseUrl/api/upload")
                .post(requestBody)
                .build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw IOException(readError(response.code, body))
                JSONObject(body)
            }
        }.getOrElse { throw it }
    }

    suspend fun listWorkspaceFiles(sessionId: String, path: String = "."): List<WorkspaceEntry> = withContext(Dispatchers.IO) {
        runCatching {
            val baseUrl = savedBaseUrl()
            val body = get("$baseUrl/api/list?session_id=$sessionId&path=${java.net.URLEncoder.encode(path, "UTF-8")}")
            val root = JSONObject(body)
            val array = root.optJSONArray("entries") ?: JSONArray()
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    add(WorkspaceEntry(
                        name = item.optString("name"),
                        path = item.optString("path"),
                        type = item.optString("type", "file"),
                        size = if (item.isNull("size")) null else item.optLong("size"),
                        mtimeNs = if (item.isNull("mtime_ns")) null else item.optLong("mtime_ns"),
                    ))
                }
            }
        }.getOrDefault(emptyList())
    }

    suspend fun downloadFileTo(sessionId: String, path: String, out: java.io.OutputStream) = withContext(Dispatchers.IO) {
        val baseUrl = savedBaseUrl()
        val url = "$baseUrl/api/file/raw?session_id=$sessionId&path=${java.net.URLEncoder.encode(path, "UTF-8")}"
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException(readError(response.code, response.body?.string()))
            response.body?.byteStream()?.copyTo(out)
                ?: throw IOException("Empty response body")
        }
    }

    suspend fun deleteWorkspaceFile(sessionId: String, path: String, recursive: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val baseUrl = savedBaseUrl()
            val body = JSONObject()
                .put("session_id", sessionId)
                .put("path", path)
                .put("recursive", recursive)
                .toString()
                .toRequestBody(jsonMediaType)
            val request = Request.Builder()
                .url("$baseUrl/api/file/delete")
                .post(body)
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException(readError(response.code, response.body?.string()))
                true
            }
        }.getOrElse { throw it }
    }

    suspend fun getMemory(): MemoryData = withContext(Dispatchers.IO) {
        val baseUrl = savedBaseUrl()
        val body = get("$baseUrl/api/memory")
        val json = JSONObject(body)
        MemoryData(
            memory = json.optString("memory", ""),
            user = json.optString("user", ""),
            soul = json.optString("soul", ""),
            memoryMtime = if (json.isNull("memory_mtime")) null else (json.optDouble("memory_mtime", 0.0) * 1000).toLong(),
            userMtime = if (json.isNull("user_mtime")) null else (json.optDouble("user_mtime", 0.0) * 1000).toLong(),
            soulMtime = if (json.isNull("soul_mtime")) null else (json.optDouble("soul_mtime", 0.0) * 1000).toLong(),
        )
    }

    suspend fun writeMemory(section: String, content: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val baseUrl = savedBaseUrl()
            val body = JSONObject()
                .put("section", section)
                .put("content", content)
                .toString()
                .toRequestBody(jsonMediaType)
            val request = Request.Builder()
                .url("$baseUrl/api/memory/write")
                .post(body)
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException(readError(response.code, response.body?.string()))
                true
            }
        }.getOrElse { throw it }
    }

    suspend fun getInsights(days: Int = 30): InsightsData = withContext(Dispatchers.IO) {
        val baseUrl = savedBaseUrl()
        val body = get("$baseUrl/api/insights?days=$days")
        val json = JSONObject(body)
        val modelsArray = json.optJSONArray("models") ?: JSONArray()
        val models = buildList {
            for (i in 0 until modelsArray.length()) {
                val m = modelsArray.optJSONObject(i) ?: continue
                add(ModelStats(
                    model = m.optString("model", "unknown"),
                    sessions = m.optInt("sessions"),
                    inputTokens = m.optInt("input_tokens"),
                    outputTokens = m.optInt("output_tokens"),
                    totalTokens = m.optInt("total_tokens"),
                    cost = m.optDouble("cost"),
                    sessionShare = m.optInt("session_share"),
                    tokenShare = m.optInt("token_share"),
                    costShare = m.optInt("cost_share"),
                ))
            }
        }
        InsightsData(
            periodDays = json.optInt("period_days"),
            totalSessions = json.optInt("total_sessions"),
            totalMessages = json.optInt("total_messages"),
            totalInputTokens = json.optInt("total_input_tokens"),
            totalOutputTokens = json.optInt("total_output_tokens"),
            totalTokens = json.optInt("total_tokens"),
            totalCost = json.optDouble("total_cost"),
            models = models,
        )
    }

    // ── Cron jobs ("Tasks") ─────────────────────────────────────────────
    suspend fun listCronJobs(): List<com.example.data.CronJob> = withContext(Dispatchers.IO) {
        runCatching {
            val baseUrl = savedBaseUrl()
            val body = get("$baseUrl/api/crons")
            val arr = JSONObject(body).optJSONArray("jobs") ?: JSONArray()
            List(arr.length()) { i -> arr.getJSONObject(i).toCronJob() }
        }.getOrDefault(emptyList())
    }

    suspend fun runCronJob(jobId: String) = withContext(Dispatchers.IO) {
        runCatching { postJobAction("${savedBaseUrl()}/api/crons/run", jobId) }
    }

    suspend fun pauseCronJob(jobId: String) = withContext(Dispatchers.IO) {
        runCatching { postJobAction("${savedBaseUrl()}/api/crons/pause", jobId) }
    }

    suspend fun resumeCronJob(jobId: String) = withContext(Dispatchers.IO) {
        runCatching { postJobAction("${savedBaseUrl()}/api/crons/resume", jobId) }
    }

    private fun postJobAction(url: String, jobId: String) {
        val body = JSONObject().put("job_id", jobId).toString().toRequestBody(jsonMediaType)
        val request = Request.Builder().url(url).post(body).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException(readError(response.code, response.body?.string()))
        }
    }

    private fun JSONObject.toCronJob(): com.example.data.CronJob {
        val schedule = optJSONObject("schedule")
        return com.example.data.CronJob(
            id = optString("id"),
            name = optString("name").ifBlank { null },
            scheduleDisplay = optString("schedule_display").ifBlank {
                schedule?.optString("expression")?.ifBlank { null }
            },
            enabled = optBoolean("enabled", true),
            state = optString("state").ifBlank { null },
            nextRunAt = if (has("next_run_at") && !isNull("next_run_at")) optLong("next_run_at") else null,
            lastRunAt = if (has("last_run_at") && !isNull("last_run_at")) optLong("last_run_at") else null,
            lastStatus = optString("last_status").ifBlank { null },
            lastError = optString("last_error").ifBlank { null },
            prompt = optString("prompt").ifBlank { null },
        )
    }

    private fun savedBaseUrl(): String {
        val raw = prefs.getString("webui_url", "").orEmpty()
        val normalized = normalizeBaseUrl(raw)
        if (normalized != _cachedBaseUrl) _cachedBaseUrl = normalized
        return _cachedBaseUrl
    }

    private fun get(url: String): String {
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException(readError(response.code, body))
            }
            return body
        }
    }

    private fun JSONObject.toMobileSession(): MobileSession {
        val updatedSeconds = when {
            has("updated_at") -> optDouble("updated_at", 0.0)
            has("created_at") -> optDouble("created_at", 0.0)
            else -> 0.0
        }
        return MobileSession(
            sessionId = optString("session_id").ifBlank { optString("id") },
            title = optString("title").ifBlank { "Untitled" },
            workspace = optString("workspace").ifBlank { null },
            model = optString("model").ifBlank { optString("model_provider").ifBlank { null } },
            updatedAt = if (updatedSeconds > 0.0) (updatedSeconds * 1000).toLong() else null,
            messageCount = when {
                has("message_count") -> optInt("message_count")
                has("messages") -> optJSONArray("messages")?.length()
                else -> null
            },
            projectId = optString("project_id").ifBlank { null },
        )
    }
}