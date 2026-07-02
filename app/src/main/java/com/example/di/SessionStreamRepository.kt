package com.example.di

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.example.data.ChatBlock
import com.example.data.ChatMessageItem
import org.json.JSONObject

/**
 * Состояние одного "живого" (стримящегося) чата. Раньше жило как приватный
 * data class внутри SessionViewModel — перенесено сюда без изменений полей,
 * только добавлено [title] (нужно для текста фонового уведомления).
 */
data class SessionInflightState(
    val blocks: SnapshotStateList<ChatBlock>,
    val baseMessages: List<ChatMessageItem>,
    val streamId: String? = null,
    var lastActivityMs: Long = System.currentTimeMillis(),
    val title: String = "",
)

/**
 * Process-scoped (не Activity-scoped!) хранилище живого состояния стриминга.
 *
 * До этого рефакторинга inflightSessions/pendingApprovals/pendingClarifies жили
 * внутри SessionViewModel, который scoped на MainActivity через `by viewModels {}`.
 * StreamGuardService — foreground Service с собственным жизненным циклом,
 * независимым от Activity — не может держать ссылку на Activity-scoped
 * ViewModel без утечки. Поэтому само состояние вынесено сюда, в singleton
 * уровня процесса; SessionViewModel по-прежнему владеет всей ЛОГИКОЙ (SSE,
 * реконнект, парсинг событий через StreamEventHandler) и просто читает/пишет
 * те же самые map'ы через эту точку доступа — поведение не меняется, меняется
 * только *где* лежат данные.
 *
 * mutableStateMapOf выбран не ради Compose (это обычный Kotlin object, а не
 * @Composable) — это тот же SnapshotStateMap, которым исходный код уже
 * пользовался, чтобы Compose UI (бейджи в MenuScreen, статус в ChatScreen)
 * реагировал на изменения. Сохраняем это же поведение один в один.
 */
object SessionStreamRepository {

    val inflightSessions = mutableStateMapOf<String, SessionInflightState>()

    // JSONObject? как значение — тот же контракт, что был в приватных полях
    // SessionViewModel: null означает "запись есть, но approval/clarify сейчас
    // не активен" (используется в паре мест как временное состояние).
    //
    // ВАЖНО: mutableMapOf() здесь был race condition — StreamGuardService читает
    // эти карты из Dispatchers.Default корутины в snapshot() каждые 4 секунды,
    // а SessionViewModel пишет в них с главного потока. Обычный LinkedHashMap не
    // потокобезопасен для конкурентного чтения/записи (в лучшем случае получим
    // ConcurrentModificationException при итерации в pendingApprovals.values.any {},
    // в худшем — тихую порчу внутренней структуры map). inflightSessions уже был
    // mutableStateMapOf (SnapshotStateMap) — это же потокобезопасное поведение
    // нужно этим двум картам: SnapshotStateMap безопасен для конкурентного
    // доступа из разных потоков (в отличие от Compose recomposition, которая
    // требует главного потока — сам доступ к данным потокобезопасен всегда).
    val pendingApprovals = mutableStateMapOf<String, JSONObject?>()
    val pendingClarifies = mutableStateMapOf<String, JSONObject?>()

    /** Есть ли вообще что охранять фоновым сервисом — активный стрим или запрос на действие. */
    val hasActiveWork: Boolean
        get() = inflightSessions.isNotEmpty() ||
            pendingApprovals.values.any { it != null } ||
            pendingClarifies.values.any { it != null }

    /** Плоский снимок для StreamGuardService/уведомлений — без Compose-типов внутри. */
    fun snapshot(): List<SessionWorkItem> {
        val ids = inflightSessions.keys + pendingApprovals.keys + pendingClarifies.keys
        return ids.distinct().map { sid ->
            SessionWorkItem(
                sessionId = sid,
                title = inflightSessions[sid]?.title.orEmpty(),
                isStreaming = inflightSessions.containsKey(sid),
                needsApproval = pendingApprovals[sid] != null,
                needsClarify = pendingClarifies[sid] != null,
            )
        }
    }
}

data class SessionWorkItem(
    val sessionId: String,
    val title: String,
    val isStreaming: Boolean,
    val needsApproval: Boolean,
    val needsClarify: Boolean,
)