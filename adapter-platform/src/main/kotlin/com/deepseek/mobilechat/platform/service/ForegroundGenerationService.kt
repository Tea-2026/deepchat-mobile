package com.deepseek.mobilechat.platform.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import com.deepseek.mobilechat.platform.R
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Foreground service that keeps a streaming generation alive while the app is
 * in the background.
 *
 * The service itself does not know how to generate anything: the application
 * layer's `BackgroundExecutionPort` registers a cancel callback for a token
 * before starting the service, and the notification's "Cancel" action invokes
 * that callback. Progress is intentionally not simulated here.
 *
 * Manifest requirements (`adapter-platform/src/main/AndroidManifest.xml`):
 * `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `POST_NOTIFICATIONS`,
 * and `android:foregroundServiceType="dataSync"` on this service.
 */
class ForegroundGenerationService : Service() {

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (val action = intent.action ?: ACTION_START) {
            ACTION_START -> {
                val token = intent.getStringExtra(EXTRA_TOKEN).orEmpty()
                val title = intent.getStringExtra(EXTRA_TITLE)
                    ?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.generation_notification_fallback_title)
                if (token.isNotEmpty()) activeTokens.add(token)
                try {
                    startForegroundCompat(buildNotification(this, title, token))
                } catch (_: RuntimeException) {
                    // Notification permission revoked / FGS launch refused:
                    // satisfy the startForegroundService contract by stopping.
                    stopSelf(startId)
                }
            }

            ACTION_CANCEL -> {
                val token = intent.getStringExtra(EXTRA_TOKEN).orEmpty()
                if (token.isNotEmpty()) {
                    activeTokens.remove(token)
                    GenerationCancelRegistry.cancel(token)
                }
            }

            ACTION_STOP -> {
                val token = intent.getStringExtra(EXTRA_TOKEN).orEmpty()
                if (token.isNotEmpty()) activeTokens.remove(token) else activeTokens.clear()
                if (activeTokens.isEmpty()) stopForegroundCompat()
            }

            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        activeTokens.clear()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        super.onDestroy()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        const val ACTION_START = "com.deepseek.mobilechat.platform.action.START_GENERATION"
        const val ACTION_CANCEL = "com.deepseek.mobilechat.platform.action.CANCEL_GENERATION"
        const val ACTION_STOP = "com.deepseek.mobilechat.platform.action.STOP_GENERATION"
        const val EXTRA_TITLE = "com.deepseek.mobilechat.platform.extra.TITLE"
        const val EXTRA_TOKEN = "com.deepseek.mobilechat.platform.extra.TOKEN"
        const val CHANNEL_ID = "generation"
        const val NOTIFICATION_ID = 1001

        private val activeTokens = ConcurrentHashMap.newKeySet<String>()
        private val requestCode = AtomicInteger(1)

        fun startIntent(context: Context, title: String, token: String): Intent =
            Intent(context, ForegroundGenerationService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_TOKEN, token)
            }

        fun cancelIntent(context: Context, token: String): Intent =
            Intent(context, ForegroundGenerationService::class.java).apply {
                action = ACTION_CANCEL
                putExtra(EXTRA_TOKEN, token)
            }

        fun stopIntent(context: Context, token: String): Intent =
            Intent(context, ForegroundGenerationService::class.java).apply {
                action = ACTION_STOP
                putExtra(EXTRA_TOKEN, token)
            }

        private fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.generation_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.generation_channel_description)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
            manager.createNotificationChannel(channel)
        }

        internal fun buildNotification(
            context: Context,
            title: String,
            token: String,
        ): Notification {
            ensureChannel(context)
            val pendingCancel = PendingIntent.getService(
                context,
                requestCode.getAndIncrement(),
                cancelIntent(context, token),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val cancelAction = Notification.Action.Builder(
                Icon.createWithResource(context, R.drawable.ic_cancel_generation),
                context.getString(R.string.generation_cancel_action),
                pendingCancel,
            ).build()

            val builder = Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_generation)
                .setContentTitle(title)
                .setContentText(context.getString(R.string.generation_notification_text))
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .addAction(cancelAction)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            }
            return builder.build()
        }
    }
}

/**
 * Token -> cancel callback bridge between [ForegroundGenerationService] (which
 * receives the notification action on the main thread) and
 * `AndroidBackgroundExecutionPort` (which owns the generation coroutine).
 */
internal object GenerationCancelRegistry {
    private val callbacks = ConcurrentHashMap<String, () -> Unit>()

    fun register(token: String, onCancel: () -> Unit) {
        callbacks[token] = onCancel
    }

    fun unregister(token: String) {
        callbacks.remove(token)
    }

    fun cancel(token: String) {
        callbacks[token]?.let { callback -> runCatching { callback() } }
    }
}
