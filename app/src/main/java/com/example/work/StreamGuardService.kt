package com.example.work

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.example.di.SessionStreamRepository
import com.example.notifications.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * "Держит" процесс живым, пока в [SessionStreamRepository] есть активная
 * работа (стрим и/или ожидание approval/clarify), и отражает это sticky-
 * уведомлением. Сам стрим (SSE, реконнект, парсинг событий) НЕ здесь — он
 * по-прежнему выполняется в корутине SessionViewModel (viewModelScope);
 * turn на сервере в любом случае выполняется независимо от клиента (см.
 * /api/chat/stream/status). Роль этого сервиса — ровно то, что нужно
 * Android-уровню: пока висит foreground-уведомление, ОС не убивает процесс
 * из-за фонового статуса приложения, и уже работающая корутина спокойно
 * доживает до onDone()/onError().
 *
 * Сервис НЕ дублирует парсинг SSE и НЕ является второй копией состояния —
 * он только читает SessionStreamRepository.snapshot() раз в несколько
 * секунд и обновляет уведомление. Как только активной работы не остаётся,
 * сервис сам себя останавливает.
 */
class StreamGuardService : Service() {

    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    // Сессии, за которыми сервис уже успел понаблюдать в этом запуске —
    // нужно, чтобы при опустошении репозитория показать финальное
    // уведомление именно по ним (а не молча исчезнуть).
    private val watchedSessionIds = mutableSetOf<String>()
    // Дедуп: не слать повторно "нужно действие" на каждый тик поллинга,
    // пока approval/clarify остаётся тем же самым нерешённым запросом.
    private val alreadyNotifiedAction = mutableSetOf<String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.ensureChannels(this)
        val notification = NotificationHelper.buildRunningNotification(
            this,
            contentText = "Сессия выполняется…",
            pendingIntent = NotificationHelper.openTasksIntent(this),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NotificationHelper.STREAM_GUARD_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NotificationHelper.STREAM_GUARD_NOTIFICATION_ID, notification)
        }
        job = scope.launch { monitorLoop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_NOT_STICKY: если систему заставили убить процесс, нет смысла
        // пересоздавать пустой сервис без данных — при следующем запуске
        // приложения/срабатывании WorkManager (Layer 3) состояние подхватится
        // само.
        return START_NOT_STICKY
    }

    private suspend fun monitorLoop() {
        while (scope.isActive) {
            val snapshot = SessionStreamRepository.snapshot()
            if (snapshot.isEmpty()) {
                finishAndStop()
                return
            }
            watchedSessionIds += snapshot.map { it.sessionId }

            val actionItems = snapshot.filter { it.needsApproval || it.needsClarify }
            for (item in actionItems) {
                if (alreadyNotifiedAction.add(item.sessionId)) {
                    NotificationHelper.showActionNeeded(
                        context = this,
                        sessionId = item.sessionId,
                        title = "Нужно ваше действие",
                        text = describeAction(item),
                    )
                }
            }
            // Сняли approval/clarify (пользователь ответил из другого места,
            // например открыл сессию в вебе) — разрешаем повторно уведомить,
            // если запрос появится снова.
            alreadyNotifiedAction.retainAll(actionItems.map { it.sessionId }.toSet())

            updateRunningNotification(snapshot)
            delay(POLL_INTERVAL_MS)
        }
    }

    private fun describeAction(item: com.example.di.SessionWorkItem): String {
        val label = item.title.ifBlank { "Сессия" }
        return when {
            item.needsApproval -> "$label ждёт подтверждения действия"
            else -> "$label — агент задал вопрос"
        }
    }

    private fun updateRunningNotification(snapshot: List<com.example.di.SessionWorkItem>) {
        val streaming = snapshot.count { it.isStreaming }
        val text = when {
            streaming == 0 -> "Ожидание действия"
            streaming == 1 -> (snapshot.firstOrNull { it.isStreaming }?.title?.ifBlank { null } ?: "Сессия") + " выполняется…"
            else -> "$streaming сессии выполняются…"
        }
        val notification = NotificationHelper.buildRunningNotification(
            this,
            contentText = text,
            pendingIntent = NotificationHelper.openTasksIntent(this),
        )
        ContextCompat.getSystemService(this, android.app.NotificationManager::class.java)
            ?.notify(NotificationHelper.STREAM_GUARD_NOTIFICATION_ID, notification)
    }

    private fun finishAndStop() {
        // Активной работы больше нет. Отдельно для каждой сессии, за которой
        // наблюдали и по которой уже не висит непоказанный action-needed,
        // отмечаем "готово" — details (успех/ошибка) не известны на этом
        // уровне (это видно из самого чата или из следующего прохода
        // SessionsCheckWorker), сообщаем только факт завершения.
        for (sid in watchedSessionIds) {
            if (sid in alreadyNotifiedAction) continue
            NotificationHelper.showSessionFinished(
                context = this,
                sessionId = sid,
                title = "Сессия завершена",
                text = "Ответ готов — откройте чат",
            )
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        job?.cancel()
        super.onDestroy()
    }

    companion object {
        private const val POLL_INTERVAL_MS = 4_000L

        fun start(context: Context) {
            val intent = Intent(context, StreamGuardService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, StreamGuardService::class.java))
        }
    }
}
