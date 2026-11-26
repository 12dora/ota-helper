package io.github.otahelper.system

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.annotation.StringRes
import io.github.otahelper.R
import io.github.otahelper.core.OtaStatus
import io.github.otahelper.core.Phase
import kotlin.math.roundToInt

/** Notification channels and the notifications the engine and watcher post. */
object Notifications {
    private const val CHANNEL_PROGRESS = "progress"
    private const val CHANNEL_ALERTS = "alerts"
    const val ID_WATCHER = 1
    private const val ID_PATCHED = 2
    private const val ID_UPDATED = 3
    private const val ID_FAILED = 4
    private const val PERCENT = 100

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(
                    CHANNEL_PROGRESS,
                    context.getString(R.string.core_channel_progress),
                    NotificationManager.IMPORTANCE_LOW,
                ),
                NotificationChannel(
                    CHANNEL_ALERTS,
                    context.getString(R.string.core_channel_alerts),
                    NotificationManager.IMPORTANCE_HIGH,
                ),
            ),
        )
    }

    /** Ongoing notification of the watcher service, following the flow phase. */
    fun watcher(context: Context, status: OtaStatus): Notification {
        val builder = builder(context, CHANNEL_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        val progress = status.progress
        when {
            status.phase == Phase.INSTALLING && progress != null -> {
                val percent = (progress * PERCENT).roundToInt().coerceIn(0, PERCENT)
                builder.setContentTitle(context.getString(R.string.core_notif_installing_title))
                    .setContentText("$percent%")
                    .setProgress(PERCENT, percent, false)
            }

            // Only the inactive-slot patch is "patching the new system"; a running cancel keeps the waiting text.
            status.phase == Phase.INSTALLED -> {
                builder.setContentTitle(context.getString(R.string.core_notif_patching_title))
                    .setProgress(0, 0, true)
            }

            else -> {
                builder.setContentTitle(context.getString(R.string.core_notif_waiting_title))
                    .setContentText(context.getString(R.string.core_notif_waiting_text))
            }
        }
        return builder.build()
    }

    fun updateWatcher(context: Context, notification: Notification) = post(context, ID_WATCHER, notification)

    fun postPatched(context: Context) {
        val reboot = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, RebootReceiver::class.java).setAction(RebootReceiver.ACTION_REBOOT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val action = Notification.Action.Builder(
            null,
            context.getString(R.string.core_notif_action_reboot),
            reboot,
        ).build()
        val notification = builder(context, CHANNEL_ALERTS)
            .setContentTitle(context.getString(R.string.core_notif_patched_title))
            .setContentText(context.getString(R.string.core_notif_patched_text))
            .setCategory(Notification.CATEGORY_STATUS)
            .setAutoCancel(true)
            .addAction(action)
            .build()
        post(context, ID_PATCHED, notification)
    }

    fun cancelPatched(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(ID_PATCHED)
    }

    fun postUpdated(context: Context, version: String) {
        val notification = builder(context, CHANNEL_ALERTS)
            .setContentTitle(context.getString(R.string.core_notif_updated_title, version))
            .setAutoCancel(true)
            .build()
        post(context, ID_UPDATED, notification)
    }

    fun postAutoPatchFailed(context: Context, message: String) =
        postFailure(context, R.string.core_notif_patch_failed_title, message)

    fun postRebootFailed(context: Context, message: String) =
        postFailure(context, R.string.core_notif_reboot_failed_title, message)

    private fun postFailure(context: Context, @StringRes title: Int, message: String) {
        val notification = builder(context, CHANNEL_ALERTS)
            .setContentTitle(context.getString(title))
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .build()
        post(context, ID_FAILED, notification)
    }

    private fun post(context: Context, id: Int, notification: Notification) {
        val granted = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) context.getSystemService(NotificationManager::class.java).notify(id, notification)
    }

    private fun builder(context: Context, channel: String): Notification.Builder {
        val builder = Notification.Builder(context, channel).setSmallIcon(R.drawable.core_ic_notification)
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { launch ->
            builder.setContentIntent(
                PendingIntent.getActivity(
                    context,
                    0,
                    launch,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        }
        return builder
    }
}
