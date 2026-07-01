package com.example.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import java.util.concurrent.TimeUnit

/**
 * Регистрация фоновых проверок (слой 3, см. SessionsCheckWorker/CronCheckWorker
 * для деталей). Вызывается один раз из MyApplication.onCreate() — WorkManager
 * сам переживает перезапуски процесса/устройства, повторный enqueue с
 * ExistingPeriodicWorkPolicy.KEEP не создаёт дублей.
 *
 * 15 минут — минимальный интервал, который вообще поддерживает
 * PeriodicWorkRequest в Android; ОС всё равно может сдвигать запуск (Doze/App
 * Standby), это ожидаемо для best-effort фоновой проверки.
 */
object WorkScheduler {

    private const val SESSIONS_WORK_NAME = "sessions_check"
    private const val CRON_WORK_NAME = "cron_check"
    private val INTERVAL_MINUTES = 15L

    fun schedule(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val sessionsRequest = PeriodicWorkRequestBuilder<SessionsCheckWorker>(
            INTERVAL_MINUTES, TimeUnit.MINUTES,
        )
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
            .build()

        val cronRequest = PeriodicWorkRequestBuilder<CronCheckWorker>(
            INTERVAL_MINUTES, TimeUnit.MINUTES,
        )
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
            .build()

        val workManager = WorkManager.getInstance(context)
        workManager.enqueueUniquePeriodicWork(
            SESSIONS_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, sessionsRequest,
        )
        workManager.enqueueUniquePeriodicWork(
            CRON_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, cronRequest,
        )
    }
}
