package com.trainwidget.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.trainwidget.prefs.WidgetPrefs
import java.time.LocalTime

private const val TAG = "AlarmScheduler"
const val ACTION_ALARM_UPDATE = "com.trainwidget.ACTION_ALARM_UPDATE"
private const val UPDATE_INTERVAL_MS = 60_000L // 1 minute

/**
 * Schedules exact per-minute alarms during active OD windows.
 * Uses setExactAndAllowWhileIdle so updates fire even in Doze mode.
 *
 * Design:
 *  - Each alarm fires AlarmReceiver, which triggers a widget refresh
 *    and reschedules the next alarm (chaining pattern).
 *  - On boot or widget enable, call scheduleIfNeeded().
 *  - When all windows are inactive, alarms are not rescheduled.
 */
object AlarmScheduler {

    fun scheduleIfNeeded(context: Context) {
        val prefs = WidgetPrefs(context)
        val pairs = prefs.getOdPairs()
        if (pairs.isEmpty()) {
            Log.d(TAG, "No OD pairs configured — skipping alarm")
            return
        }

        val hasActiveNow = pairs.any { it.isActiveNow() }
        val hasUpcoming = pairs.any {
            val now = LocalTime.now()
            it.activeFrom.isAfter(now) && it.activeFrom.isBefore(now.plusHours(12))
        }

        if (hasActiveNow || hasUpcoming) {
            schedule(context)
        } else {
            Log.d(TAG, "No active or upcoming windows today — not scheduling")
        }
    }

    fun schedule(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingIntent(context)
        val triggerAt = SystemClock.elapsedRealtime() + UPDATE_INTERVAL_MS

        try {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAt,
                pi
            )
            Log.d(TAG, "Scheduled next alarm in 60s")
        } catch (e: SecurityException) {
            Log.e(TAG, "Exact alarm permission denied — falling back to inexact", e)
            alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
        }
    }

    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(pendingIntent(context))
        Log.d(TAG, "Alarm cancelled")
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = ACTION_ALARM_UPDATE
        }
        return PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

/**
 * Receives the per-minute alarm, triggers a widget refresh,
 * then reschedules the next alarm if still within an active window.
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_ALARM_UPDATE) return
        Log.d(TAG, "Alarm fired — refreshing widget")

        // Trigger widget update
        val refreshIntent = Intent(context, TrainWidgetProvider::class.java).apply {
            action = ACTION_REFRESH
        }
        context.sendBroadcast(refreshIntent)

        // Reschedule next alarm only if still in (or approaching) an active window
        AlarmScheduler.scheduleIfNeeded(context)
    }
}
