package com.trainwidget.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.StrikethroughSpan
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import com.trainwidget.R
import com.trainwidget.api.PtvApiClient
import com.trainwidget.config.ConfigActivity
import com.trainwidget.data.Departure
import com.trainwidget.data.OdPair
import com.trainwidget.prefs.WidgetPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val TAG = "NextTrain"
const val ACTION_REFRESH = "com.trainwidget.ACTION_REFRESH"

class TrainWidgetProvider : AppWidgetProvider() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        Log.d(TAG, "onUpdate: ${appWidgetIds.size} widget(s)")
        appWidgetIds.forEach { updateWidget(context, appWidgetManager, it) }
        AlarmScheduler.scheduleIfNeeded(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH || intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d(TAG, "Received refresh/boot intent")
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, TrainWidgetProvider::class.java))
            ids.forEach { updateWidget(context, manager, it) }
            AlarmScheduler.scheduleIfNeeded(context)
        }
    }

    override fun onEnabled(context: Context) {
        AlarmScheduler.scheduleIfNeeded(context)
    }

    override fun onDisabled(context: Context) {
        AlarmScheduler.cancel(context)
    }

    // ── Update logic ──────────────────────────────────────────────────────

    private fun updateWidget(context: Context, manager: AppWidgetManager, widgetId: Int) {
        val prefs = WidgetPrefs(context)

        val activePair = prefs.activeOdPairs().firstOrNull()
            ?: prefs.getOdPairs().firstOrNull()
            ?: demoOdPair()

        // If server not configured and no OD pairs, show demo.
        if (!prefs.serverConfigured && prefs.getOdPairs().isEmpty()) {
            showLoadingState(context, manager, widgetId, activePair)
            scope.launch {
                renderDepartures(
                    context = context,
                    manager = manager,
                    widgetId = widgetId,
                    pair = activePair,
                    departures = demoDepartures(),
                    isDemoData = true
                )
            }
            return
        }

        // Show loading state immediately, then fetch
        showLoadingState(context, manager, widgetId, activePair)

        scope.launch {
            val client = PtvApiClient(prefs.devId, prefs.apiKey)
            val departures = client.getDeparturesFromServer(
                serverUrl = prefs.serverUrl,
                stopId = activePair.originStopId,
                destinationStopId = activePair.destinationStopId,
                directionId = activePair.directionId
            )

            if (departures.isEmpty() && !client.isServerReachable(prefs.serverUrl)) {
                showErrorState(
                    context = context,
                    manager = manager,
                    widgetId = widgetId,
                    message = "Cannot reach server. Check Wi-Fi and server URL"
                )
                return@launch
            }

            renderDepartures(
                context = context,
                manager = manager,
                widgetId = widgetId,
                pair = activePair,
                departures = departures,
                isDemoData = false
            )
        }
    }

    // ── RemoteViews builders ──────────────────────────────────────────────

    private fun showLoadingState(
        context: Context,
        manager: AppWidgetManager,
        widgetId: Int,
        pair: OdPair
    ) {
        val views = RemoteViews(context.packageName, R.layout.widget_layout)
        views.setTextViewText(R.id.tv_route_label, "${pair.originName} → ${pair.destinationName}")
        views.setTextViewText(R.id.tv_last_updated, "Updating…")
        views.setTextViewText(R.id.tv_primary_minutes, "--")
        views.setTextViewText(R.id.tv_primary_time, "Updating")
        views.setViewVisibility(R.id.tv_secondary_1, View.GONE)
        views.setViewVisibility(R.id.tv_secondary_separator, View.GONE)
        views.setViewVisibility(R.id.tv_secondary_2, View.GONE)
        views.setViewVisibility(R.id.tv_no_trains, View.GONE)
        manager.updateAppWidget(widgetId, views)
    }

    private fun showErrorState(
        context: Context,
        manager: AppWidgetManager,
        widgetId: Int,
        message: String
    ) {
        val views = RemoteViews(context.packageName, R.layout.widget_layout)
        views.setTextViewText(R.id.tv_route_label, "Next Train")
        views.setTextViewText(R.id.tv_last_updated, "")
        views.setTextViewText(R.id.tv_primary_minutes, "--")
        views.setTextViewText(R.id.tv_primary_time, "--:--")
        views.setViewVisibility(R.id.tv_secondary_1, View.GONE)
        views.setViewVisibility(R.id.tv_secondary_separator, View.GONE)
        views.setViewVisibility(R.id.tv_secondary_2, View.GONE)
        views.setViewVisibility(R.id.tv_no_trains, View.VISIBLE)
        views.setTextViewText(R.id.tv_no_trains, message)

        // Tap → open config
        val configIntent = Intent(context, ConfigActivity::class.java)
        val pi = PendingIntent.getActivity(context, 0, configIntent, PendingIntent.FLAG_IMMUTABLE)
        views.setOnClickPendingIntent(R.id.widget_root, pi)
        manager.updateAppWidget(widgetId, views)

        val prefs = WidgetPrefs(context)
        if (prefs.notificationModeEnabled) {
            CommuteNotificationManager.showStatus(context, "Next Train", message)
        } else {
            CommuteNotificationManager.clear(context)
        }
    }

    private fun showIdleState(
        context: Context,
        manager: AppWidgetManager,
        widgetId: Int,
        prefs: WidgetPrefs
    ) {
        val nextPair = prefs.getOdPairs().firstOrNull()
        val views = RemoteViews(context.packageName, R.layout.widget_layout)
        views.setTextViewText(
            R.id.tv_route_label,
            nextPair?.let { "${it.originName} → ${it.destinationName}" } ?: "Next Train"
        )
        views.setTextViewText(R.id.tv_last_updated, "")
        views.setTextViewText(R.id.tv_primary_minutes, "--")
        views.setTextViewText(R.id.tv_primary_time, "--:--")
        views.setViewVisibility(R.id.tv_secondary_1, View.GONE)
        views.setViewVisibility(R.id.tv_secondary_separator, View.GONE)
        views.setViewVisibility(R.id.tv_secondary_2, View.GONE)
        views.setViewVisibility(R.id.tv_no_trains, View.VISIBLE)
        val msg = nextPair?.let {
            "Active ${it.activeFrom}–${it.activeTo}"
        } ?: "No routes configured"
        views.setTextViewText(R.id.tv_no_trains, msg)
        manager.updateAppWidget(widgetId, views)

        if (prefs.notificationModeEnabled) {
            CommuteNotificationManager.showStatus(
                context,
                nextPair?.let { "${it.originName} -> ${it.destinationName}" } ?: "Next Train",
                msg
            )
        } else {
            CommuteNotificationManager.clear(context)
        }
    }

    private fun renderDepartures(
        context: Context,
        manager: AppWidgetManager,
        widgetId: Int,
        pair: OdPair,
        departures: List<Departure>,
        isDemoData: Boolean
    ) {
        val views = RemoteViews(context.packageName, R.layout.widget_layout)
        views.setTextViewText(R.id.tv_route_label, "${pair.originName} → ${pair.destinationName}")
        val now = DateTimeFormatter.ofPattern("HH:mm")
            .withZone(ZoneId.of("Australia/Melbourne"))
            .format(Instant.now())
        views.setTextViewText(R.id.tv_last_updated, if (isDemoData) "DEMO ↻ $now" else "↻ $now")

        if (departures.isEmpty()) {
            views.setTextViewText(R.id.tv_primary_minutes, "--")
            views.setTextViewText(R.id.tv_primary_time, "--:--")
            views.setViewVisibility(R.id.tv_secondary_1, View.GONE)
            views.setViewVisibility(R.id.tv_secondary_separator, View.GONE)
            views.setViewVisibility(R.id.tv_secondary_2, View.GONE)
            views.setViewVisibility(R.id.tv_no_trains, View.VISIBLE)
            views.setTextViewText(R.id.tv_no_trains, "No trains found")
        } else {
            val primary = departures[0]
            views.setTextViewText(R.id.tv_primary_minutes, departureMinutesText(primary))
            views.setTextViewText(R.id.tv_primary_time, "(${primary.expectedTime})")

            val second = departures.getOrNull(1)
            if (second != null) {
                views.setTextViewText(R.id.tv_secondary_1, compactDepartureText(second))
                views.setViewVisibility(R.id.tv_secondary_1, View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.tv_secondary_1, View.GONE)
            }

            val third = departures.getOrNull(2)
            if (third != null) {
                views.setTextViewText(R.id.tv_secondary_2, compactDepartureText(third))
                views.setViewVisibility(R.id.tv_secondary_2, View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.tv_secondary_2, View.GONE)
            }

            views.setViewVisibility(
                R.id.tv_secondary_separator,
                if (second != null && third != null) View.VISIBLE else View.GONE
            )

            views.setViewVisibility(R.id.tv_no_trains, View.GONE)
        }

        // Tap to refresh
        val refreshIntent = Intent(context, TrainWidgetProvider::class.java).apply {
            action = ACTION_REFRESH
        }
        val pi = PendingIntent.getBroadcast(
            context, widgetId, refreshIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_root, pi)

        manager.updateAppWidget(widgetId, views)
        val prefs = WidgetPrefs(context)
        if (prefs.notificationModeEnabled) {
            CommuteNotificationManager.showDepartures(context, pair, departures, isDemoData)
        } else {
            CommuteNotificationManager.clear(context)
        }
    }

    private fun formatDepartureTime(dep: Departure): CharSequence {
        if (!dep.hasRealtimeTimeChange) return dep.expectedTime

        val expected = dep.expectedTime
        val scheduledBracket = " (${dep.scheduledTime})"
        val out = SpannableStringBuilder(expected).append(scheduledBracket)
        val start = expected.length + 2 // skip leading " ("
        val end = start + dep.scheduledTime.length
        out.setSpan(StrikethroughSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return out
    }

    private fun departureMinutesText(dep: Departure): String = when {
        dep.minutesUntilDeparture <= 0 -> "Now"
        dep.minutesUntilDeparture == 1L -> "1m"
        dep.minutesUntilDeparture > 120 -> "${dep.minutesUntilDeparture / 60}h"
        else -> "${dep.minutesUntilDeparture}m"
    }

    private fun compactDepartureText(dep: Departure): CharSequence {
        val out = SpannableStringBuilder()
        val mins = departureMinutesText(dep)
        out.append(mins)
        out.setSpan(
            StyleSpan(Typeface.BOLD),
            0,
            out.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        val timeStart = out.length
        out.append(" (${dep.expectedTime})")
        out.setSpan(
            RelativeSizeSpan(0.88f),
            timeStart,
            out.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return out
    }

    private fun demoOdPair(): OdPair = OdPair(
        id = "demo-pair",
        label = "Demo route",
        originStopId = 1065,
        originName = "Fairfield",
        destinationStopId = 1104,
        destinationName = "Jolimont",
        activeFrom = LocalTime.MIN,
        activeTo = LocalTime.MAX,
        directionId = -1
    )

    private fun demoDepartures(): List<Departure> {
        val now = LocalTime.now(ZoneId.of("Australia/Melbourne"))
        val fmt = DateTimeFormatter.ofPattern("HH:mm")

        fun mk(minutesFromNow: Long, delayMinutes: Int, platform: String): Departure {
            val scheduled = now.plusMinutes(minutesFromNow).format(fmt)
            val estimated = if (delayMinutes == 0) {
                null
            } else {
                now.plusMinutes(minutesFromNow + delayMinutes).format(fmt)
            }
            return Departure(
                scheduledTime = scheduled,
                estimatedTime = estimated,
                delayMinutes = delayMinutes,
                platformNumber = platform,
                minutesUntilDeparture = minutesFromNow + delayMinutes
            )
        }

        return listOf(
            mk(minutesFromNow = 3, delayMinutes = 2, platform = "1"),
            mk(minutesFromNow = 11, delayMinutes = 0, platform = "2"),
            mk(minutesFromNow = 19, delayMinutes = -1, platform = "1")
        )
    }
}
