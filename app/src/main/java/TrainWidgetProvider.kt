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
const val ACTION_CYCLE_ROUTE = "com.trainwidget.ACTION_CYCLE_ROUTE"

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
        if (intent.action == ACTION_CYCLE_ROUTE) {
            Log.d(TAG, "Received cycle route intent")
            val prefs = WidgetPrefs(context)
            prefs.cycleToNextRoute()
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, TrainWidgetProvider::class.java))
            ids.forEach { updateWidget(context, manager, it) }
            AlarmScheduler.scheduleIfNeeded(context)
            return
        }

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
        val activePairNow = prefs.activeOdPairs().firstOrNull()
        val currentActiveRouteId = activePairNow?.id
        val lastActiveRouteId = prefs.getLastActiveRouteId()

        // Clear dismissal whenever a new active window starts for any route.
        if (currentActiveRouteId != null && currentActiveRouteId != lastActiveRouteId) {
            prefs.clearNotificationDismissal()
        }

        // If the last active route is no longer active, clear its dismissal flag so it can notify again next time.
        if (lastActiveRouteId != null && (currentActiveRouteId == null || currentActiveRouteId != lastActiveRouteId)) {
            // Find the last active route object
            val lastPair = prefs.getOdPairs().find { it.id == lastActiveRouteId }
            if (lastPair != null && !lastPair.isActiveNow()) {
                prefs.clearNotificationDismissal(lastActiveRouteId)
            }
        }

        prefs.setLastActiveRouteId(currentActiveRouteId)

        val activePair = resolveSelectedPair(prefs, activePairNow)

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

            // Notifications are based on the route currently active in its time window,
            // not necessarily the route selected for widget display.
            val notificationPair = activePairNow
            if (notificationPair == null) {
                CommuteNotificationManager.clear(context)
            } else if (notificationPair.id == activePair.id) {
                CommuteNotificationManager.showDepartures(context, notificationPair, departures, false)
            } else {
                val notificationDepartures = client.getDeparturesFromServer(
                    serverUrl = prefs.serverUrl,
                    stopId = notificationPair.originStopId,
                    destinationStopId = notificationPair.destinationStopId,
                    directionId = notificationPair.directionId
                )
                CommuteNotificationManager.showDepartures(context, notificationPair, notificationDepartures, false)
            }
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
        views.setViewVisibility(R.id.tv_secondary_1, View.INVISIBLE)
        views.setViewVisibility(R.id.tv_secondary_separator, View.INVISIBLE)
        views.setViewVisibility(R.id.tv_secondary_2, View.INVISIBLE)
        views.setViewVisibility(R.id.tv_no_trains, View.GONE)
        applyTapActions(context, views, widgetId)
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
        views.setViewVisibility(R.id.tv_secondary_1, View.INVISIBLE)
        views.setViewVisibility(R.id.tv_secondary_separator, View.INVISIBLE)
        views.setViewVisibility(R.id.tv_secondary_2, View.INVISIBLE)
        views.setViewVisibility(R.id.tv_no_trains, View.VISIBLE)
        views.setTextViewText(R.id.tv_no_trains, message)

        applyTapActions(context, views, widgetId)
        manager.updateAppWidget(widgetId, views)

        // No notification for loading state; handled by per-route logic in showDepartures
        CommuteNotificationManager.clear(context)
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
        views.setViewVisibility(R.id.tv_secondary_1, View.INVISIBLE)
        views.setViewVisibility(R.id.tv_secondary_separator, View.INVISIBLE)
        views.setViewVisibility(R.id.tv_secondary_2, View.INVISIBLE)
        views.setViewVisibility(R.id.tv_no_trains, View.VISIBLE)
        val msg = nextPair?.let {
            "Active ${it.activeFrom}–${it.activeTo}"
        } ?: "No routes configured"
        views.setTextViewText(R.id.tv_no_trains, msg)
        applyTapActions(context, views, widgetId)
        manager.updateAppWidget(widgetId, views)

        // No notification for idle state; handled by per-route logic in showDepartures
        CommuteNotificationManager.clear(context)
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
            views.setViewVisibility(R.id.tv_secondary_1, View.INVISIBLE)
            views.setViewVisibility(R.id.tv_secondary_separator, View.INVISIBLE)
            views.setViewVisibility(R.id.tv_secondary_2, View.INVISIBLE)
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
                views.setViewVisibility(R.id.tv_secondary_1, View.INVISIBLE)
            }

            val third = departures.getOrNull(2)
            if (third != null) {
                views.setTextViewText(R.id.tv_secondary_2, compactDepartureText(third))
                views.setViewVisibility(R.id.tv_secondary_2, View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.tv_secondary_2, View.INVISIBLE)
            }

            views.setViewVisibility(
                R.id.tv_secondary_separator,
                if (second != null && third != null) View.VISIBLE else View.INVISIBLE
            )

            views.setViewVisibility(R.id.tv_no_trains, View.GONE)
        }

        applyTapActions(context, views, widgetId)
        manager.updateAppWidget(widgetId, views)
    }

    private fun resolveSelectedPair(prefs: WidgetPrefs, activePairNow: OdPair?): OdPair {
        val configuredPairs = prefs.getOdPairs()
        if (configuredPairs.isEmpty()) {
            prefs.setSelectedRouteId(null)
            return demoOdPair()
        }

        val selected = prefs.getSelectedRouteId()?.let { id ->
            configuredPairs.firstOrNull { it.id == id }
        }
        if (selected != null) return selected

        val fallback = activePairNow ?: configuredPairs.first()
        prefs.setSelectedRouteId(fallback.id)
        return fallback
    }

    private fun applyTapActions(context: Context, views: RemoteViews, widgetId: Int) {
        val refreshIntent = Intent(context, TrainWidgetProvider::class.java).apply {
            action = ACTION_REFRESH
        }
        val refreshPi = PendingIntent.getBroadcast(
            context,
            widgetId,
            refreshIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.tap_refresh_zone, refreshPi)

        val cycleIntent = Intent(context, TrainWidgetProvider::class.java).apply {
            action = ACTION_CYCLE_ROUTE
        }
        val cyclePi = PendingIntent.getBroadcast(
            context,
            widgetId + 10_000,
            cycleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.tap_cycle_zone, cyclePi)
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
