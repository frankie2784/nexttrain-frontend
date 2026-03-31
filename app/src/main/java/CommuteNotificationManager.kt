package com.trainwidget.widget

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.trainwidget.R
import com.trainwidget.config.ConfigActivity
import com.trainwidget.data.Departure
import com.trainwidget.data.OdPair

private const val CHANNEL_ID = "next_train_departures"
private const val CHANNEL_NAME = "Train departures"
private const val CHANNEL_DESC = "Live departures for lock screen notification"
private const val NOTIFICATION_ID = 1001

object CommuteNotificationManager {

    fun clear(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    fun showDepartures(
        context: Context,
        pair: OdPair,
        departures: List<Departure>,
        isDemoData: Boolean
    ) {
        ensureChannel(context)
        val openIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, ConfigActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (isDemoData) {
            "DEMO ${pair.originName} -> ${pair.destinationName}"
        } else {
            "${pair.originName} -> ${pair.destinationName}"
        }

        val content = if (departures.isEmpty()) {
            "No upcoming trains"
        } else {
            departures.take(3).joinToString(" • ") { dep ->
                val mins = when {
                    dep.minutesUntilDeparture <= 0 -> "now"
                    dep.minutesUntilDeparture == 1L -> "1m"
                    else -> "${dep.minutesUntilDeparture}m"
                }
                val platform = dep.platformNumber?.let { " P$it" } ?: ""
                "${dep.displayTime} ($mins$platform)"
            }
        }

        val lines = if (departures.isEmpty()) {
            listOf("No upcoming trains")
        } else {
            departures.take(3).map { dep ->
                val status = when {
                    dep.delayMinutes > 1 -> " +${dep.delayMinutes}m"
                    dep.delayMinutes < -1 -> " ${dep.delayMinutes}m"
                    else -> ""
                }
                val platform = dep.platformNumber?.let { " platform $it" } ?: ""
                "${dep.displayTime}${status}${platform}"
            }
        }

        val style = NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n"))

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(style)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Android 13+ notification permission may not be granted yet.
        }
    }

    fun showStatus(context: Context, title: String, message: String) {
        ensureChannel(context)
        val openIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, ConfigActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Android 13+ notification permission may not be granted yet.
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = CHANNEL_DESC
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }
}
