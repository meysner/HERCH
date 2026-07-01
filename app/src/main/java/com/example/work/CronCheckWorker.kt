package com.example.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.di.AppModule
import com.example.network.HermesApiClient
import com.example.notifications.NotificationHelper

private const val PREFS_NAME = "herch_hermes"
private const val PREF_LAST_CRON_CHECK = "last_cron_check_ts"

/**
 * Раз в ~15 минут дёргает GET /api/crons/recent?since=<последняя проверка> —
 * сервер сам отдаёт только новые завершения (api/routes.py::_handle_cron_recent),
 * дифф считать самим не нужно. Это единственный способ узнать о cron-джобах,
 * которые стартуют на сервере сами по себе — у мобильного клиента для них
 * никогда не было живого SSE-соединения, даже когда приложение открыто.
 */
class CronCheckWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedUrl = prefs.getString("webui_url", "").orEmpty()
        if (savedUrl.isBlank()) return Result.success()

        val httpClient = AppModule.provideOkHttpClient(prefs)
        val apiClient = HermesApiClient(prefs, httpClient)

        val nowMillis = System.currentTimeMillis()
        // Первый запуск (нет сохранённого значения) — берём только последний
        // час назад, а не "всё время", чтобы не завалить пользователя пуш-ами
        // про старые задачи при первой установке фичи.
        // Храним как Long millis, а не Float seconds: epoch-секунды (~1.7e9)
        // не влезают в 24-битную мантиссу Float без потери точности, что
        // сдвигало бы окно `since` на десятки-сотни секунд и грозило либо
        // дублировать уведомления, либо пропускать завершения на границе.
        val sinceMillis = prefs.getLong(PREF_LAST_CRON_CHECK, nowMillis - 3_600_000L)
        val since = sinceMillis / 1000.0

        val completions = runCatching { apiClient.listRecentCronCompletions(since) }
            .getOrElse { return Result.retry() }

        for (completion in completions) {
            if (!completion.toastNotifications) continue // джоба замьючена — как и в вебе, молчим
            val isError = completion.status == "error"
            NotificationHelper.showCronCompletion(
                context = applicationContext,
                jobId = completion.jobId,
                sessionId = completion.sessionId,
                title = completion.name?.ifBlank { null } ?: "Задача",
                text = if (isError) "Завершилась с ошибкой" else "Выполнена",
                isError = isError,
            )
        }

        prefs.edit().putLong(PREF_LAST_CRON_CHECK, nowMillis).apply()
        return Result.success()
    }
}
