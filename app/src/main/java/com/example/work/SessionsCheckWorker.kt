package com.example.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.data.SessionAttentionKind
import com.example.di.AppModule
import com.example.network.HermesApiClient
import com.example.notifications.NotificationHelper

private const val PREFS_NAME = "herch_hermes"
private const val PREF_MSG_COUNT_PREFIX = "msg_count_"
private const val PREF_ATTENTION_NOTIFIED_PREFIX = "attention_notified_"
// Не спамим о новом сообщении в сессии, которую пользователь ещё не открыл
// после ПРЕДЫДУЩЕГО прохода этого воркера — используем тот же ключ, что и
// SessionViewModel для бейджа "непрочитано" (unread_$sid), сброс произойдёт
// сам при следующем openSession().

/**
 * Слой 3 ("холодный" опрос): работает даже если процесс приложения не
 * запущен вовсе — в отличие от StreamGuardService (слой 2), которому нужен
 * живой процесс, и live-стрима в SessionViewModel (слой 1), которому нужен
 * открытый экран. Это единственный слой, который узнает о cron-сессиях,
 * запущенных без участия этого телефона — у них никогда не было живого SSE
 * на этом устройстве.
 *
 * GET /api/sessions уже используется в SessionViewModel.refreshSessions() —
 * здесь тот же эндпоинт и те же ключи SharedPreferences (msg_count_$sid,
 * unread_$sid), чтобы состояние было согласовано между фоном и открытым
 * приложением, а не два независимых счётчика.
 */
class SessionsCheckWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedUrl = prefs.getString("webui_url", "").orEmpty()
        if (savedUrl.isBlank()) return Result.success() // ещё не залогинен — нечего опрашивать

        val httpClient = AppModule.provideOkHttpClient(prefs)
        val apiClient = HermesApiClient(prefs, httpClient)

        val sessionsResult = apiClient.listSessions()
        val sessions = when (sessionsResult) {
            is com.example.data.SessionsResult.Success -> sessionsResult.sessions
            is com.example.data.SessionsResult.Failure -> {
                // HermesApiClient.readError() кладёт "HTTP 401: ..." в начало
                // сообщения (см. network/NetworkUtils.kt). 401 значит кука
                // истекла/сброшена — повторный запуск через 15 минут её не
                // починит, ретраить бессмысленно. Любая другая ошибка (сеть,
                // 5xx) — временная, пробуем ещё раз позже.
                return if (sessionsResult.message.startsWith("HTTP 401")) {
                    Result.success()
                } else {
                    Result.retry()
                }
            }
        }

        val editor = prefs.edit()
        for (session in sessions) {
            val sid = session.sessionId
            if (sid.isBlank()) continue

            // ── Новое сообщение (тот же дифф, что использует foreground-поллинг) ──
            val prevCount = prefs.getInt("$PREF_MSG_COUNT_PREFIX$sid", -1)
            val newCount = session.messageCount ?: -1
            if (prevCount >= 0 && newCount > prevCount && !session.isStreaming) {
                editor.putBoolean("unread_$sid", true)
                NotificationHelper.showSessionFinished(
                    context = applicationContext,
                    sessionId = sid,
                    title = session.title.ifBlank { "Сессия" },
                    text = "Новое сообщение",
                )
            }
            if (newCount >= 0) editor.putInt("$PREF_MSG_COUNT_PREFIX$sid", newCount)

            // ── Нужно подтверждение/уточнение ──────────────────────────────
            val attention = session.attention
            val attentionKey = "$PREF_ATTENTION_NOTIFIED_PREFIX$sid"
            if (attention != null) {
                val marker = "${attention.kind}:${attention.count}"
                if (prefs.getString(attentionKey, null) != marker) {
                    editor.putString(attentionKey, marker)
                    val text = when (attention.kind) {
                        SessionAttentionKind.APPROVAL -> "${session.title.ifBlank { "Сессия" }} ждёт подтверждения действия"
                        SessionAttentionKind.CLARIFY -> "${session.title.ifBlank { "Сессия" }} — агент задал вопрос"
                    }
                    NotificationHelper.showActionNeeded(
                        context = applicationContext,
                        sessionId = sid,
                        title = "Нужно ваше действие",
                        text = text,
                    )
                }
            } else {
                // Действие снято (approved/clarified/сессия закрыта) — разрешаем
                // уведомить заново, если оно появится снова.
                editor.remove(attentionKey)
            }
        }
        editor.apply()

        return Result.success()
    }
}
