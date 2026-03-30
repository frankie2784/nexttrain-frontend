package com.trainwidget.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
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

private const val TAG = "TrainWidget"
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

        // If credentials are missing and there are no configured routes, show a stable demo route.
        if (!prefs.credentialsSet && prefs.getOdPairs().isEmpty()) {
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
            if (!prefs.credentialsSet) {
                renderDepartures(
                    context = context,
                    manager = manager,
                    widgetId = widgetId,
                    pair = activePair,
                    departures = demoDepartures(),
                    isDemoData = true
                )
            } else {
                val client = PtvApiClient(prefs.devId, prefs.apiKey)
                val departures = fetchAndFilter(client, activePair)
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
    }

    // ── Fetch & filter ────────────────────────────────────────────────────

    /**
     * Fetches departures from the PTV API and filters to services that also
     * stop at the destination station. Returns up to 3 upcoming departures.
     */
    private suspend fun fetchAndFilter(client: PtvApiClient, pair: OdPair): List<Departure> {
        val raw = client.getDepartures(pair.originStopId, pair.directionId)

        val now = Instant.now()
        return raw
            .filter { dep ->
                // Only include services with a departure time in the future
                val depTime = dep.estimated_departure_utc ?: dep.scheduled_departure_utc
                if (depTime != null) Instant.parse(depTime).isAfter(now) else false
            }
            .sortedBy { dep ->
                val t = dep.estimated_departure_utc ?: dep.scheduled_departure_utc
                Instant.parse(t).epochSecond
            }
            .take(3)
            .map { dep ->
                val schedLocal = PtvApiClient.parseToMelbourneTime(dep.scheduled_departure_utc)
                val estLocal = PtvApiClient.parseToMelbourneTime(dep.estimated_departure_utc)
                val delay = PtvApiClient.delayMinutes(
                    dep.scheduled_departure_utc,
                    dep.estimated_departure_utc
                )
                val depInstant = Instant.parse(
                    dep.estimated_departure_utc ?: dep.scheduled_departure_utc!!
                )
                val minutesUntil = (depInstant.epochSecond - now.epochSecond) / 60

                Departure(
                    scheduledTime = schedLocal ?: "??:??",
                    estimatedTime = if (delay != 0) estLocal else null,
                    delayMinutes = delay,
                    platformNumber = dep.platform_number,
                    minutesUntilDeparture = minutesUntil
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
        views.setTextViewText(R.id.tv_route_label, "Train Widget")
        views.setTextViewText(R.id.tv_last_updated, "")
        views.setViewVisibility(R.id.tv_no_trains, View.VISIBLE)
        views.setTextViewText(R.id.tv_no_trains, message)
        hideRow(views, R.id.row_departure_1)
        hideRow(views, R.id.row_departure_2)
        hideRow(views, R.id.row_departure_3)

        // Tap → open config
        val configIntent = Intent(context, ConfigActivity::class.java)
        val pi = PendingIntent.getActivity(context, 0, configIntent, PendingIntent.FLAG_IMMUTABLE)
        views.setOnClickPendingIntent(R.id.widget_root, pi)
        manager.updateAppWidget(widgetId, views)

        val prefs = WidgetPrefs(context)
        if (prefs.notificationModeEnabled) {
            CommuteNotificationManager.showStatus(context, "Train Widget", message)
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
            nextPair?.let { "${it.originName} → ${it.destinationName}" } ?: "Train Widget"
        )
        views.setTextViewText(R.id.tv_last_updated, "")
        views.setViewVisibility(R.id.tv_no_trains, View.VISIBLE)
        val msg = nextPair?.let {
            "Active ${it.activeFrom}–${it.activeTo}"
        } ?: "No routes configured"
        views.setTextViewText(R.id.tv_no_trains, msg)
        hideRow(views, R.id.row_departure_1)
        hideRow(views, R.id.row_departure_2)
        hideRow(views, R.id.row_departure_3)
        manager.updateAppWidget(widgetId, views)

        if (prefs.notificationModeEnabled) {
            CommuteNotificationManager.showStatus(
                context,
                nextPair?.let { "${it.originName} -> ${it.destinationName}" } ?: "Train Widget",
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

        val rowIds = listOf(R.id.row_departure_1, R.id.row_departure_2, R.id.row_departure_3)

        if (departures.isEmpty()) {
            views.setViewVisibility(R.id.tv_no_trains, View.VISIBLE)
            views.setTextViewText(R.id.tv_no_trains, "No trains found")
            rowIds.forEach { hideRow(views, it) }
        } else {
            views.setViewVisibility(R.id.tv_no_trains, View.GONE)
            rowIds.forEachIndexed { index, rowId ->
                val dep = departures.getOrNull(index)
                if (dep != null) {
                    bindDepartureRow(context, views, rowId, dep)
                } else {
                    hideRow(views, rowId)
                }
            }
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

    private fun bindDepartureRow(context: Context, parentViews: RemoteViews, containerId: Int, dep: Departure) {
        val row = RemoteViews(context.packageName, R.layout.widget_departure_row)
        row.setTextViewText(R.id.tv_departure_time, dep.displayTime)

        val minsText = when {
            dep.minutesUntilDeparture <= 0 -> "Now"
            dep.minutesUntilDeparture == 1L -> "in 1 min"
            else -> "in ${dep.minutesUntilDeparture} min"
        }
        row.setTextViewText(R.id.tv_minutes_away, minsText)

        val platform = dep.platformNumber?.let { "Plat $it" } ?: ""
        row.setTextViewText(R.id.tv_platform, platform)

        when {
            dep.isDelayed -> {
                row.setViewVisibility(R.id.tv_delay, View.VISIBLE)
                row.setTextViewText(R.id.tv_delay, "+${dep.delayMinutes}m")
                row.setInt(R.id.tv_delay, "setBackgroundResource", R.drawable.delay_badge_bg)
            }
            dep.isEarly -> {
                row.setViewVisibility(R.id.tv_delay, View.VISIBLE)
                row.setTextViewText(R.id.tv_delay, "${dep.delayMinutes}m")
                row.setInt(R.id.tv_delay, "setBackgroundResource", R.drawable.early_badge_bg)
            }
            else -> {
                row.setViewVisibility(R.id.tv_delay, View.GONE)
            }
        }

        parentViews.removeAllViews(containerId)
        parentViews.addView(containerId, row)
    }

    private fun hideRow(views: RemoteViews, containerId: Int) {
        views.removeAllViews(containerId)
    }

    private fun demoOdPair(): OdPair = OdPair(
        id = "demo-pair",
        label = "Demo route",
        originStopId = 1066,
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
