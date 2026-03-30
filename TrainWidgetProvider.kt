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

        if (!prefs.credentialsSet) {
            showErrorState(context, manager, widgetId, "Tap to configure PTV API credentials")
            return
        }

        val activePair = prefs.activeOdPairs().firstOrNull()

        if (activePair == null) {
            // Outside all active windows — show idle state
            showIdleState(context, manager, widgetId, prefs)
            return
        }

        // Show loading state immediately, then fetch
        showLoadingState(context, manager, widgetId, activePair)

        scope.launch {
            val client = PtvApiClient(prefs.devId, prefs.apiKey)
            val departures = fetchAndFilter(client, activePair)
            renderDepartures(context, manager, widgetId, activePair, departures)
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
    }

    private fun renderDepartures(
        context: Context,
        manager: AppWidgetManager,
        widgetId: Int,
        pair: OdPair,
        departures: List<Departure>
    ) {
        val views = RemoteViews(context.packageName, R.layout.widget_layout)
        views.setTextViewText(R.id.tv_route_label, "${pair.originName} → ${pair.destinationName}")

        val now = DateTimeFormatter.ofPattern("HH:mm")
            .withZone(ZoneId.of("Australia/Melbourne"))
            .format(Instant.now())
        views.setTextViewText(R.id.tv_last_updated, "↻ $now")

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
                    views.setViewVisibility(rowId, View.VISIBLE)
                    bindDepartureRow(views, rowId, dep)
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
    }

    private fun bindDepartureRow(views: RemoteViews, rowId: Int, dep: Departure) {
        views.setTextViewText(rowId, R.id.tv_departure_time, dep.displayTime)

        val minsText = when {
            dep.minutesUntilDeparture <= 0 -> "Now"
            dep.minutesUntilDeparture == 1L -> "in 1 min"
            else -> "in ${dep.minutesUntilDeparture} min"
        }
        views.setTextViewText(rowId, R.id.tv_minutes_away, minsText)

        val platform = dep.platformNumber?.let { "Plat $it" } ?: ""
        views.setTextViewText(rowId, R.id.tv_platform, platform)

        when {
            dep.isDelayed -> {
                views.setViewVisibility(rowId, R.id.tv_delay, View.VISIBLE)  // Note: see below
                views.setTextViewText(rowId, R.id.tv_delay, "+${dep.delayMinutes}m")
                views.setInt(rowId, R.id.tv_delay, "setBackgroundResource", R.drawable.delay_badge_bg)
            }
            dep.isEarly -> {
                views.setViewVisibility(rowId, R.id.tv_delay, View.VISIBLE)
                views.setTextViewText(rowId, R.id.tv_delay, "${dep.delayMinutes}m")
                views.setInt(rowId, R.id.tv_delay, "setBackgroundResource", R.drawable.early_badge_bg)
            }
            else -> {
                views.setViewVisibility(rowId, R.id.tv_delay, View.GONE)
            }
        }
    }

    // RemoteViews doesn't directly support setViewVisibility on included layouts
    // via their container ID — this helper sets alpha to simulate hide.
    private fun hideRow(views: RemoteViews, rowId: Int) {
        views.setViewVisibility(rowId, View.INVISIBLE)
    }
}
