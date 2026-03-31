package com.trainwidget.config

import android.Manifest
import android.app.TimePickerDialog
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class ConfigActivity : AppCompatActivity() {

    private lateinit var prefs: WidgetPrefs
    private lateinit var adapter: OdPairAdapter

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    companion object {
        private const val REQ_POST_NOTIFICATIONS = 100
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
        val btnPickFrom = dialogView.findViewById<Button>(R.id.btn_pick_from)
        val btnPickTo = dialogView.findViewById<Button>(R.id.btn_pick_to)

        val stations = MelbourneStations.ALL
        val stationNames = stations.map { it.name }
        val stationAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, stationNames)
            .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        spinnerOrigin.adapter = stationAdapter
        spinnerDest.adapter = stationAdapter

        var fromTime = existing?.activeFrom ?: LocalTime.of(6, 0)
        var toTime = existing?.activeTo ?: LocalTime.of(10, 0)
        val fmt = DateTimeFormatter.ofPattern("HH:mm")

        fun updateTimeLabels() {
            tvFrom.text = fromTime.format(fmt)
            tvTo.text = toTime.format(fmt)
        }
        updateTimeLabels()

        // Pre-fill existing values
        existing?.let { pair ->
            etLabel.setText(pair.label)
            stations.indexOfFirst { it.stopId == pair.originStopId }.takeIf { it >= 0 }
                ?.let { spinnerOrigin.setSelection(it) }
            stations.indexOfFirst { it.stopId == pair.destinationStopId }.takeIf { it >= 0 }
                ?.let { spinnerDest.setSelection(it) }
        }

        btnPickFrom.setOnClickListener {
            TimePickerDialog(this, { _, h, m ->
                fromTime = LocalTime.of(h, m)
                updateTimeLabels()
            }, fromTime.hour, fromTime.minute, true).show()
        }

        btnPickTo.setOnClickListener {
            TimePickerDialog(this, { _, h, m ->
                toTime = LocalTime.of(h, m)
                updateTimeLabels()
            }, toTime.hour, toTime.minute, true).show()
        }

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Add route" else "Edit route")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val label = etLabel.text?.toString()?.trim()
                    ?.ifBlank { null }
                    ?: "${stations[spinnerOrigin.selectedItemPosition].name} → ${stations[spinnerDest.selectedItemPosition].name}"
                val origin: Station = stations[spinnerOrigin.selectedItemPosition]
                val dest: Station = stations[spinnerDest.selectedItemPosition]

                if (origin.stopId == dest.stopId) {
                    Toast.makeText(this, "Origin and destination must differ", Toast.LENGTH_SHORT).show()
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
                    activeTo = toTime
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
}

// ── RecyclerView Adapter ──────────────────────────────────────────────────

class OdPairAdapter(
    private val items: MutableList<OdPair>,
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
        holder.tvWindow.text = "${pair.activeFrom.format(fmt)} – ${pair.activeTo.format(fmt)}"
        holder.btnEdit.setOnClickListener { onEdit(pair) }
        holder.btnDelete.setOnClickListener { onDelete(pair) }
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
