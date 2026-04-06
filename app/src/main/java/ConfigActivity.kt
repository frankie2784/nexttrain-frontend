package com.trainwidget.config

import android.Manifest
import android.app.TimePickerDialog
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.trainwidget.R
import com.trainwidget.data.MelbourneStations
import com.trainwidget.data.OdPair
import com.trainwidget.data.Station
import com.trainwidget.prefs.WidgetPrefs
import com.trainwidget.widget.AlarmScheduler
import com.trainwidget.widget.ACTION_REFRESH
import com.trainwidget.widget.TrainWidgetProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalTime
import java.time.DayOfWeek
import java.time.format.DateTimeFormatter

private data class ReachableStation(
    val station_gtfs_id: String,
    val display_name: String,
    val public_stop_id: String?,
)

private data class ReachableResponse(
    val stations: List<ReachableStation> = emptyList(),
)

private data class StationCatalogStation(
    val display_name: String,
    val public_stop_id: String?,
)

private data class StationCatalogResponse(
    val stations: List<StationCatalogStation> = emptyList(),
)

class ConfigActivity : AppCompatActivity() {

    private lateinit var prefs: WidgetPrefs
    private lateinit var adapter: OdPairAdapter

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    companion object {
        private const val REQ_POST_NOTIFICATIONS = 100
        const val EXTRA_PAIR_ID = "extra_pair_id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_config)
        setResult(RESULT_CANCELED) // default until user saves

        prefs = WidgetPrefs(this)

        // Determine widget ID from intent (may be INVALID if opened from app)
        appWidgetId = intent.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        requestNotificationPermissionIfNeeded()

        findViewById<ImageButton>(R.id.btn_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        setupOdPairsSection()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

        if (!granted) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQ_POST_NOTIFICATIONS
            )
        }
    }

    // ── OD Pairs list ─────────────────────────────────────────────────────

    private fun setupOdPairsSection() {
        val rv = findViewById<RecyclerView>(R.id.rv_od_pairs)
        val fab = findViewById<FloatingActionButton>(R.id.fab_add_od)
        val btnDone = findViewById<Button>(R.id.btn_done)

        adapter = OdPairAdapter(
            prefs.getOdPairs().toMutableList(),
            onOpen = { pair -> openRouteDetails(pair) },
            onDelete = { pair ->
                prefs.removeOdPair(pair.id)
                adapter.remove(pair)
            },
            onEdit = { pair -> showOdPairDialog(pair) }
        )
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        fab.setOnClickListener { showOdPairDialog(null) }

        btnDone.setOnClickListener {
            triggerWidgetUpdate()
            finish()
        }
    }

    // ── Add / Edit dialog ─────────────────────────────────────────────────

    private fun showOdPairDialog(existing: OdPair?) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_od_pair, null)

        val etLabel = dialogView.findViewById<TextInputEditText>(R.id.et_label)
        val spinnerOrigin = dialogView.findViewById<Spinner>(R.id.spinner_origin)
        val spinnerDest = dialogView.findViewById<Spinner>(R.id.spinner_destination)
        val tvFrom = dialogView.findViewById<TextView>(R.id.tv_time_from)
        val tvTo = dialogView.findViewById<TextView>(R.id.tv_time_to)
        val dayChecks = listOf(
            DayOfWeek.MONDAY.value to dialogView.findViewById<MaterialButton>(R.id.cb_mon),
            DayOfWeek.TUESDAY.value to dialogView.findViewById<MaterialButton>(R.id.cb_tue),
            DayOfWeek.WEDNESDAY.value to dialogView.findViewById<MaterialButton>(R.id.cb_wed),
            DayOfWeek.THURSDAY.value to dialogView.findViewById<MaterialButton>(R.id.cb_thu),
            DayOfWeek.FRIDAY.value to dialogView.findViewById<MaterialButton>(R.id.cb_fri),
            DayOfWeek.SATURDAY.value to dialogView.findViewById<MaterialButton>(R.id.cb_sat),
            DayOfWeek.SUNDAY.value to dialogView.findViewById<MaterialButton>(R.id.cb_sun),
        )

        var originStations: List<Station> = MelbourneStations.ALL
        var destStations: List<Station> = originStations
        var filterJob: Job? = null
        val serverUrl = prefs.serverUrl

        // Track which dest stop to restore after origin changes filter
        var pendingOriginStopId: Int? = existing?.originStopId
        var pendingDestStopId: Int? = existing?.destinationStopId

        fun applyDestStations(originStopId: Int, candidates: List<Station>, preferredStopId: Int? = null, consumePending: Boolean = false) {
            destStations = candidates.filter { it.stopId != originStopId }
            val newAdapter = ArrayAdapter(this, R.layout.spinner_item_contrast, destStations.map { it.name })
                .also { it.setDropDownViewResource(R.layout.spinner_dropdown_item_contrast) }
            spinnerDest.adapter = newAdapter
            val targetStopId = preferredStopId ?: pendingDestStopId
            if (targetStopId != null) {
                val idx = destStations.indexOfFirst { it.stopId == targetStopId }
                if (idx >= 0) spinnerDest.setSelection(idx)
                if (consumePending && pendingDestStopId == targetStopId) {
                    pendingDestStopId = null
                }
            }
        }

        fun updateDestSpinner(originStopId: Int) {
            val currentDestStopId = spinnerDest.selectedItemPosition
                .takeIf { it in destStations.indices }
                ?.let { destStations[it].stopId }
            val preferredDestStopId = pendingDestStopId ?: currentDestStopId

            // Show all stations immediately while the server query runs
            applyDestStations(originStopId, originStations, preferredDestStopId, consumePending = false)

            if (serverUrl.isBlank()) return  // no server configured, keep full list

            spinnerDest.isEnabled = false
            filterJob?.cancel()
            filterJob = lifecycleScope.launch {
                val filteredByLine = withContext(Dispatchers.IO) {
                    try {
                        val url = java.net.URL("$serverUrl/reachable_destinations?stop_id=$originStopId")
                        val json = url.readText()
                        val response = com.google.gson.Gson().fromJson(json, ReachableResponse::class.java)
                        response.stations.map { s ->
                            val pubId = s.public_stop_id?.toIntOrNull()
                            Station(
                                name = s.display_name,
                                stopId = pubId ?: 0,
                            )
                        }.filter { it.stopId > 0 }
                    } catch (e: Exception) {
                        android.util.Log.w("ConfigActivity", "Reachable dest fetch failed", e)
                        emptyList()
                    }
                }

                if (filteredByLine.isNotEmpty()) {
                    applyDestStations(originStopId, filteredByLine, preferredDestStopId, consumePending = true)
                } else if (pendingDestStopId != null) {
                    // No filtered result; consume pending so later refreshes don't keep forcing it.
                    pendingDestStopId = null
                }
                spinnerDest.isEnabled = true
            }
        }

        fun applyOriginStations(candidates: List<Station>) {
            if (candidates.isEmpty()) return
            originStations = candidates.distinctBy { it.stopId }.sortedBy { it.name }

            val adapter = ArrayAdapter(this, R.layout.spinner_item_contrast, originStations.map { it.name })
                .also { it.setDropDownViewResource(R.layout.spinner_dropdown_item_contrast) }
            spinnerOrigin.adapter = adapter

            val targetStopId = pendingOriginStopId
            if (targetStopId != null) {
                val idx = originStations.indexOfFirst { it.stopId == targetStopId }
                if (idx >= 0) spinnerOrigin.setSelection(idx)
                pendingOriginStopId = null
            }

            if (originStations.isNotEmpty()) {
                val selected = spinnerOrigin.selectedItemPosition
                val idx = if (selected in originStations.indices) selected else 0
                updateDestSpinner(originStations[idx].stopId)
            }
        }

        if (serverUrl.isBlank()) {
            applyOriginStations(originStations)
        } else {
            spinnerOrigin.isEnabled = false
            spinnerDest.isEnabled = false
            lifecycleScope.launch {
                val catalogStations = withContext(Dispatchers.IO) {
                    try {
                        val url = java.net.URL("$serverUrl/stations")
                        val json = url.readText()
                        val response = com.google.gson.Gson().fromJson(json, StationCatalogResponse::class.java)
                        response.stations.mapNotNull { s ->
                            val pubId = s.public_stop_id?.toIntOrNull()
                            if (pubId == null || s.display_name.isBlank()) null
                            else Station(name = s.display_name, stopId = pubId)
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("ConfigActivity", "Station catalog fetch failed", e)
                        emptyList()
                    }
                }

                if (catalogStations.isNotEmpty()) {
                    applyOriginStations(catalogStations)
                } else {
                    applyOriginStations(originStations)
                }
                spinnerOrigin.isEnabled = true
                spinnerDest.isEnabled = true
            }
        }

        spinnerOrigin.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                if (position in originStations.indices) {
                    updateDestSpinner(originStations[position].stopId)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        var fromTime = existing?.activeFrom ?: LocalTime.of(6, 0)
        var toTime = existing?.activeTo ?: LocalTime.of(10, 0)
        val fmt = DateTimeFormatter.ofPattern("HH:mm")

        fun updateTimeLabels() {
            tvFrom.text = fromTime.format(fmt)
            tvTo.text = toTime.format(fmt)
        }
        updateTimeLabels()

        // Pre-fill existing values — origin setSelection triggers the listener which restores dest
        existing?.let { pair ->
            etLabel.setText(pair.label)
            dayChecks.forEach { (day, cb) -> cb.isChecked = day in pair.activeDays }
        }
        if (existing == null) {
            dayChecks.forEach { (_, cb) -> cb.isChecked = true }
        }

        tvFrom.setOnClickListener {
            val picker = TimePickerDialog(this, R.style.Theme_TrainWidget_WhiteTimePickerDialog, { _, h, m ->
                fromTime = LocalTime.of(h, m)
                updateTimeLabels()
            }, fromTime.hour, fromTime.minute, true)
            picker.show()
            picker.window?.setBackgroundDrawable(ColorDrawable(ContextCompat.getColor(this, R.color.nt_surface)))
        }

        tvTo.setOnClickListener {
            val picker = TimePickerDialog(this, R.style.Theme_TrainWidget_WhiteTimePickerDialog, { _, h, m ->
                toTime = LocalTime.of(h, m)
                updateTimeLabels()
            }, toTime.hour, toTime.minute, true)
            picker.show()
            picker.window?.setBackgroundDrawable(ColorDrawable(ContextCompat.getColor(this, R.color.nt_surface)))
        }

        AlertDialog.Builder(this, R.style.ThemeOverlay_TrainWidget_WhiteAlertDialog)
            .setTitle(if (existing == null) "Add route" else "Edit route")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                if (spinnerOrigin.selectedItemPosition !in originStations.indices) {
                    Toast.makeText(this, "Choose a valid origin station", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (spinnerDest.selectedItemPosition !in destStations.indices) {
                    Toast.makeText(this, "Choose a valid destination station", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val origin: Station = originStations[spinnerOrigin.selectedItemPosition]
                val dest: Station = destStations[spinnerDest.selectedItemPosition]
                val label = etLabel.text?.toString()?.trim()
                    ?.ifBlank { null }
                    ?: "${origin.name} → ${dest.name}"

                if (origin.stopId == dest.stopId) {
                    Toast.makeText(this, "Origin and destination must differ", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val activeDays = dayChecks.filter { it.second.isChecked }.map { it.first }.toSet()
                if (activeDays.isEmpty()) {
                    Toast.makeText(this, "Select at least one active day", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val pair = OdPair(
                    id = existing?.id ?: WidgetPrefs.newId(),
                    label = label,
                    originStopId = origin.stopId,
                    originName = origin.name,
                    destinationStopId = dest.stopId,
                    destinationName = dest.name,
                    activeFrom = fromTime,
                    activeTo = toTime,
                    activeDays = activeDays,
                )
                if (existing == null) {
                    prefs.addOdPair(pair)
                    adapter.add(pair)
                } else {
                    prefs.updateOdPair(pair)
                    adapter.update(pair)
                }
                AlarmScheduler.scheduleIfNeeded(this)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Finish ────────────────────────────────────────────────────────────

    private fun triggerWidgetUpdate() {
        val intent = Intent(this, TrainWidgetProvider::class.java).apply {
            action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
            if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(appWidgetId))
            }
        }
        sendBroadcast(intent)

        // Also trigger refresh path so lockscreen notification updates immediately.
        sendBroadcast(Intent(this, TrainWidgetProvider::class.java).apply { action = ACTION_REFRESH })

        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            val result = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            setResult(RESULT_OK, result)
        }
    }

    private fun openRouteDetails(pair: OdPair) {
        startActivity(
            Intent(this, RouteDeparturesActivity::class.java)
                .putExtra(EXTRA_PAIR_ID, pair.id)
        )
    }
}

// ── RecyclerView Adapter ──────────────────────────────────────────────────

class OdPairAdapter(
    private val items: MutableList<OdPair>,
    private val onOpen: (OdPair) -> Unit,
    private val onDelete: (OdPair) -> Unit,
    private val onEdit: (OdPair) -> Unit
) : RecyclerView.Adapter<OdPairAdapter.VH>() {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvLabel: TextView = view.findViewById(R.id.tv_od_label)
        val tvRoute: TextView = view.findViewById(R.id.tv_od_route)
        val tvWindow: TextView = view.findViewById(R.id.tv_od_window)
        val btnEdit: ImageButton = view.findViewById(R.id.btn_edit)
        val btnDelete: ImageButton = view.findViewById(R.id.btn_delete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_od_pair, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val pair = items[position]
        val fmt = DateTimeFormatter.ofPattern("HH:mm")
        holder.tvLabel.text = pair.label
        holder.tvRoute.text = "${pair.originName} → ${pair.destinationName}"
        holder.tvWindow.text = "${formatDays(pair.activeDays)}  ${pair.activeFrom.format(fmt)} – ${pair.activeTo.format(fmt)}"
        holder.itemView.setOnClickListener { onOpen(pair) }
        holder.btnEdit.setOnClickListener { onEdit(pair) }
        holder.btnDelete.setOnClickListener { onDelete(pair) }
    }

    private fun formatDays(days: Set<Int>): String {
        val all = (1..7).toSet()
        if (days == all) return "Every day"
        if (days == setOf(1, 2, 3, 4, 5)) return "Weekdays"
        if (days == setOf(6, 7)) return "Weekends"

        val labels = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        return days.sorted().joinToString(",") { d -> labels.getOrElse(d - 1) { "?" } }
    }

    override fun getItemCount() = items.size

    fun add(pair: OdPair) { items.add(pair); notifyItemInserted(items.size - 1) }
    fun remove(pair: OdPair) {
        val i = items.indexOfFirst { it.id == pair.id }
        if (i >= 0) { items.removeAt(i); notifyItemRemoved(i) }
    }
    fun update(pair: OdPair) {
        val i = items.indexOfFirst { it.id == pair.id }
        if (i >= 0) { items[i] = pair; notifyItemChanged(i) }
    }
}
