package com.trainwidget.config

import android.content.Intent
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StrikethroughSpan
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.trainwidget.R
import com.trainwidget.api.PtvApiClient
import com.trainwidget.data.Departure
import com.trainwidget.data.OdPair
import com.trainwidget.prefs.WidgetPrefs
import com.trainwidget.widget.ACTION_REFRESH
import com.trainwidget.widget.TrainWidgetProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RouteDeparturesActivity : AppCompatActivity() {

    companion object {
        private const val REFRESH_INTERVAL_MS = 60_000L
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var refreshJob: Job? = null

    private lateinit var prefs: WidgetPrefs
    private lateinit var tvRouteTitle: TextView
    private lateinit var tvStatus: TextView
    private lateinit var departuresContainer: LinearLayout
    private lateinit var tvEmpty: TextView
    private lateinit var switchRouteNotification: androidx.appcompat.widget.SwitchCompat

    private var pair: OdPair? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_route_departures)

        prefs = WidgetPrefs(this)

        tvRouteTitle = findViewById(R.id.tv_route_title)
        tvStatus = findViewById(R.id.tv_status)
        departuresContainer = findViewById(R.id.departures_container)
        tvEmpty = findViewById(R.id.tv_empty)
        switchRouteNotification = findViewById(R.id.switch_route_notification)

        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<Button>(R.id.btn_back_home).setOnClickListener { finish() }
        findViewById<Button>(R.id.btn_refresh).setOnClickListener { loadDepartures() }

        val pairId = intent.getStringExtra(ConfigActivity.EXTRA_PAIR_ID)
        pair = prefs.getOdPairs().firstOrNull { it.id == pairId }

        if (pair == null) {
            tvRouteTitle.text = "Route not found"
            tvStatus.text = "Please return and re-open a route"
            showEmpty("No route selected")
            return
        }
        val selectedPair = pair!!
        tvRouteTitle.text = "${selectedPair.originName} → ${selectedPair.destinationName}"

        // Set up per-route notification toggle
        switchRouteNotification.isChecked = selectedPair.notificationsEnabled
        switchRouteNotification.setOnCheckedChangeListener { _, isChecked ->
            // Update the OdPair and persist
            val updatedPair = (pair ?: selectedPair).copy(notificationsEnabled = isChecked)
            prefs.updateOdPair(updatedPair)
            pair = updatedPair

            // Refresh widget/notification immediately after toggling route notification setting.
            sendBroadcast(Intent(this, TrainWidgetProvider::class.java).apply { action = ACTION_REFRESH })
        }
    }

    override fun onStart() {
        super.onStart()
        startAutoRefresh()
    }

    override fun onStop() {
        stopAutoRefresh()
        super.onStop()
    }

    private fun startAutoRefresh() {
        if (refreshJob?.isActive == true) return

        refreshJob = scope.launch {
            while (isActive) {
                loadDepartures()
                delay(REFRESH_INTERVAL_MS)
            }
        }
    }

    private fun stopAutoRefresh() {
        refreshJob?.cancel()
        refreshJob = null
    }

    private fun loadDepartures() {
        val selectedPair = pair ?: return
        tvStatus.text = "Loading next departures..."
        departuresContainer.removeAllViews()
        tvEmpty.visibility = View.GONE

        scope.launch {
            val client = PtvApiClient(prefs.devId, prefs.apiKey)
            val departures = withContext(Dispatchers.IO) {
                client.getDeparturesFromServer(
                    serverUrl = prefs.serverUrl,
                    stopId = selectedPair.originStopId,
                    destinationStopId = selectedPair.destinationStopId,
                    directionId = selectedPair.directionId,
                    maxResults = 3
                )
            }

            if (departures.isEmpty()) {
                val reachable = withContext(Dispatchers.IO) { client.isServerReachable(prefs.serverUrl) }
                if (!reachable) {
                    showEmpty("Cannot reach server. Check Wi-Fi and server URL")
                } else {
                    showEmpty("No trains found")
                }
                tvStatus.text = "Updated just now"
            } else {
                renderDepartures(departures)
                tvStatus.text = "Showing next ${departures.size} trains"
            }
        }
    }

    private fun renderDepartures(departures: List<Departure>) {
        departuresContainer.removeAllViews()
        tvEmpty.visibility = View.GONE

        departures.forEachIndexed { index, dep ->
            val row = layoutInflater.inflate(R.layout.item_route_departure, departuresContainer, false)

            val time = row.findViewById<TextView>(R.id.tv_dep_time)
            val mins = row.findViewById<TextView>(R.id.tv_dep_mins)
            val meta = row.findViewById<TextView>(R.id.tv_dep_meta)

            val minsText = when {
                dep.minutesUntilDeparture <= 0 -> "Now"
                dep.minutesUntilDeparture == 1L -> "1 min"
                else -> "${dep.minutesUntilDeparture} min"
            }
            val delayText = when {
                dep.delayMinutes > 0 -> "Delayed ${dep.delayMinutes} min"
                dep.delayMinutes < 0 -> "${-dep.delayMinutes} min early"
                else -> "On time"
            }

            time.text = formatDepartureTime(dep)
            mins.text = minsText
            meta.text = delayText

            departuresContainer.addView(row)
        }
    }

    private fun showEmpty(message: String) {
        departuresContainer.removeAllViews()
        tvEmpty.text = message
        tvEmpty.visibility = View.VISIBLE
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

    override fun onDestroy() {
        stopAutoRefresh()
        super.onDestroy()
        scope.cancel()
    }
}
