package com.deepseek.mobilechat.platform.service

import android.content.Context
import android.content.Intent
import com.deepseek.mobilechat.application.BackgroundExecutionPort
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Runs a streaming generation under [ForegroundGenerationService].
 *
 * Lifecycle:
 * 1. register the cancel callback for a fresh token,
 * 2. start the foreground service (the 5-second `startForegroundService` window),
 * 3. run [block],
 * 4. always unregister the callback and tell the service to drop the token.
 *
 * If the OS refuses to start a foreground service (for example the app is in
 * the background on Android 12+ without an exemption) we deliberately degrade
 * to running [block] in the current process instead of failing the generation:
 * it may still be cancelled, and the app still gets its result if it stays
 * alive.
 */
class AndroidBackgroundExecutionPort(context: Context) : BackgroundExecutionPort {

    private val appContext: Context = context.applicationContext

    override suspend fun <T> run(title: String, onCancel: () -> Unit, block: suspend () -> T): T {
        val token = UUID.randomUUID().toString()
        startForegroundService(title, token, onCancel)
        try {
            return block()
        } finally {
            GenerationCancelRegistry.unregister(token)
            runCatching {
                appContext.startService(ForegroundGenerationService.stopIntent(appContext, token))
            }
        }
    }

    /**
     * Wraps `startForegroundService` in a continuation so the callback is
     * registered before the service can receive its cancel action. A failure to
     * start is not fatal; see the class comment.
     */
    private suspend fun startForegroundService(
        title: String,
        token: String,
        onCancel: () -> Unit,
    ): Boolean = suspendCoroutine { continuation ->
        GenerationCancelRegistry.register(token, onCancel)
        try {
            val intent: Intent = ForegroundGenerationService.startIntent(appContext, title, token)
            appContext.startForegroundService(intent)
            continuation.resume(true)
        } catch (_: IllegalStateException) {
            GenerationCancelRegistry.unregister(token)
            continuation.resume(false)
        } catch (_: SecurityException) {
            GenerationCancelRegistry.unregister(token)
            continuation.resume(false)
        }
    }
}
