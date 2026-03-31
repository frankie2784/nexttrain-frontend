package com.trainwidget.prefs

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.trainwidget.data.OdPair
import java.time.LocalTime
import java.util.UUID

private const val PREFS_NAME = "com.trainwidget.prefs"
private const val KEY_DEV_ID = "ptv_dev_id"
private const val KEY_API_KEY = "ptv_api_key"
private const val KEY_OD_PAIRS = "od_pairs"
private const val KEY_NOTIFICATION_MODE = "notification_mode_enabled"
private const val KEY_SERVER_URL = "server_url"

/**
 * Thin wrapper around SharedPreferences for all widget configuration.
 */
class WidgetPrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    // ── PTV credentials ───────────────────────────────────────────────────

    var devId: String
        get() = prefs.getString(KEY_DEV_ID, "") ?: ""
        set(v) = prefs.edit().putString(KEY_DEV_ID, v).apply()

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "") ?: ""
        set(v) = prefs.edit().putString(KEY_API_KEY, v).apply()

    val credentialsSet: Boolean get() = devId.isNotBlank() && apiKey.isNotBlank()

    // ── RPi4 server URL ───────────────────────────────────────────────────

    var serverUrl: String
        get() = prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
        set(v) = prefs.edit().putString(KEY_SERVER_URL, v).apply()

    val serverConfigured: Boolean get() = serverUrl.isNotBlank()

    var notificationModeEnabled: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATION_MODE, true)
        set(v) = prefs.edit().putBoolean(KEY_NOTIFICATION_MODE, v).apply()

    // ── OD Pairs ──────────────────────────────────────────────────────────

    fun getOdPairs(): List<OdPair> {
        val json = prefs.getString(KEY_OD_PAIRS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<OdPairDto>>() {}.type
            val dtos: List<OdPairDto> = gson.fromJson(json, type)
            dtos.map { it.toOdPair() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveOdPairs(pairs: List<OdPair>) {
        val dtos = pairs.map { OdPairDto.from(it) }
        prefs.edit().putString(KEY_OD_PAIRS, gson.toJson(dtos)).apply()
    }

    fun addOdPair(pair: OdPair) = saveOdPairs(getOdPairs() + pair)

    fun removeOdPair(id: String) = saveOdPairs(getOdPairs().filter { it.id != id })

    fun updateOdPair(pair: OdPair) = saveOdPairs(
        getOdPairs().map { if (it.id == pair.id) pair else it }
    )

    /** Returns all OD pairs currently in their active time window. */
    fun activeOdPairs(): List<OdPair> = getOdPairs().filter { it.isActiveNow() }

    // ── Gson-serialisable DTO (avoids java.time serialization issues) ─────

    private data class OdPairDto(
        val id: String,
        val label: String,
        val originStopId: Int,
        val originName: String,
        val destinationStopId: Int,
        val destinationName: String,
        val activeFromHour: Int,
        val activeFromMinute: Int,
        val activeToHour: Int,
        val activeToMinute: Int,
        val directionId: Int
    ) {
        fun toOdPair() = OdPair(
            id = id,
            label = label,
            originStopId = originStopId,
            originName = originName,
            destinationStopId = destinationStopId,
            destinationName = destinationName,
            activeFrom = LocalTime.of(activeFromHour, activeFromMinute),
            activeTo = LocalTime.of(activeToHour, activeToMinute),
            directionId = directionId
        )

        companion object {
            fun from(p: OdPair) = OdPairDto(
                id = p.id,
                label = p.label,
                originStopId = p.originStopId,
                originName = p.originName,
                destinationStopId = p.destinationStopId,
                destinationName = p.destinationName,
                activeFromHour = p.activeFrom.hour,
                activeFromMinute = p.activeFrom.minute,
                activeToHour = p.activeTo.hour,
                activeToMinute = p.activeTo.minute,
                directionId = p.directionId
            )
        }
    }

    companion object {
        const val DEFAULT_SERVER_URL = "http://frankipi:5050"
        fun newId(): String = UUID.randomUUID().toString()
    }
}
