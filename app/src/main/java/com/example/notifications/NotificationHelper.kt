package com.example.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.MainActivity

/**
 * Все каналы, ID и deep-link'и уведомлений в одном месте — используется и
 * StreamGuardService (живой стрим/action-needed), и фоновыми WorkManager-
 * воркерами (SessionsCheckWorker/CronCheckWorker), чтобы не разъезжались
 * форматы и не дублировались notification ID между слоями.
 */
object NotificationChannels {
    // Sticky, пока сессия активно стримится в фоне — тихий (LOW), чтобы не
    // дёргать пользователя на каждое обновление токенов.
    const val RUNNING = "sessions_running"

    // Нужно подтверждение/уточнение от пользователя — HIGH, со звуком.
    const val ACTION_NEEDED = "sessions_action_needed"

    // Завершения cron-задач.
    const val TASKS = "cron_tasks"
}

object NotificationHelper {

    // Единственное sticky-уведомление StreamGuardService — оно одно на все
    // одновременно активные сессии (сводка), не по одному на сессию.
    const val STREAM_GUARD_NOTIFICATION_ID = 1001

    const val EXTRA_SESSION_ID = "open_session_id"
    const val EXTRA_SCREEN = "open_screen"
    const val SCREEN_TASKS = "tasks"

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        manager.createNotificationChannel(
            NotificationChannel(
                NotificationChannels.RUNNING,
                "Сессии выполняются",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Показывает, что агент работает в фоне, пока приложение свёрнуто"
                setShowBadge(false)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                NotificationChannels.ACTION_NEEDED,
                "Нужно ваше действие",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Агент ждёт подтверждения действия или ответа на вопрос"
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                NotificationChannels.TASKS,
                "Задачи (cron)",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Завершения и ошибки запланированных задач"
            }
        )
    }

    /** Стабильный ID для уведомления конкретной сессии (не пересекается с cron/guard ID). */
    fun sessionNotificationId(sessionId: String): Int = 2_000_000 + (sessionId.hashCode() and 0x0FFFFF)

    /** Стабильный ID для уведомления конкретной cron-джобы. */
    fun cronNotificationId(jobId: String): Int = 3_000_000 + (jobId.hashCode() and 0x0FFFFF)

    fun openSessionIntent(context: Context, sessionId: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_SESSION_ID, sessionId)
        }
        return PendingIntent.getActivity(
            context,
            sessionId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun openTasksIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_SCREEN, SCREEN_TASKS)
        }
        return PendingIntent.getActivity(
            context,
            SCREEN_TASKS.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun buildRunningNotification(context: Context, contentText: String, pendingIntent: PendingIntent): Notification =
        NotificationCompat.Builder(context, NotificationChannels.RUNNING)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Herch работает")
            .setContentText(contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .build()

    fun showActionNeeded(context: Context, sessionId: String, title: String, text: String) {
        ensureChannels(context)
        val notification = NotificationCompat.Builder(context, NotificationChannels.ACTION_NEEDED)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openSessionIntent(context, sessionId))
            .build()
        notify(context, sessionNotificationId(sessionId), notification)
    }

    fun showSessionFinished(context: Context, sessionId: String, title: String, text: String) {
        ensureChannels(context)
        val notification = NotificationCompat.Builder(context, NotificationChannels.RUNNING)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(openSessionIntent(context, sessionId))
            .build()
        notify(context, sessionNotificationId(sessionId), notification)
    }

    fun showCronCompletion(context: Context, jobId: String, sessionId: String?, title: String, text: String, isError: Boolean) {
        ensureChannels(context)
        val intent = if (sessionId != null) openSessionIntent(context, sessionId) else openTasksIntent(context)
        val notification = NotificationCompat.Builder(context, NotificationChannels.TASKS)
            .setSmallIcon(
                if (isError) android.R.drawable.stat_notify_error else android.R.drawable.stat_sys_download_done
            )
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setPriority(if (isError) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(intent)
            .build()
        notify(context, cronNotificationId(jobId), notification)
    }

    fun cancel(context: Context, notificationId: Int) {
        NotificationManagerCompatSafe(context).cancel(notificationId)
    }

    private fun notify(context: Context, id: Int, notification: Notification) {
        NotificationManagerCompatSafe(context).notify(id, notification)
    }
}

// androidx.core.app.NotificationManagerCompat.notify() требует POST_NOTIFICATIONS
// на API 33+ и кидает SecurityException, если пользователь не выдал разрешение.
// Фоновые воркеры/сервис не должны падать из-за этого — тихо игнорируем.
private class NotificationManagerCompatSafe(private val context: Context) {
    private val delegate = androidx.core.app.NotificationManagerCompat.from(context)

    fun notify(id: Int, notification: Notification) {
        runCatching { delegate.notify(id, notification) }
    }

    fun cancel(id: Int) {
        runCatching { delegate.cancel(id) }
    }
}
