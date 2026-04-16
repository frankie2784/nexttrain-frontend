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
private const val KEY_DISMISSED_PREFIX = "dismissed_"
private const val KEY_DISMISSED_ROUTE_ID = "dismissed_route_id"
private const val KEY_LAST_ACTIVE_ROUTE_ID = "last_active_route_id"
private const val KEY_SELECTED_ROUTE_ID = "selected_route_id"

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
            val pairs = dtos.map { it.toOdPair() }.map { migrateLegacyStopIds(it) }
            if (pairs != dtos.map { it.toOdPair() }) {
                saveOdPairs(pairs)
            }
            pairs
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

    /** Returns route id currently selected for widget display, if any. */
    fun getSelectedRouteId(): String? {
        return prefs.getString(KEY_SELECTED_ROUTE_ID, null)
    }

    /** Persist selected route id for widget display. */
    fun setSelectedRouteId(routeId: String?) {
        prefs.edit().apply {
            if (routeId == null) {
                remove(KEY_SELECTED_ROUTE_ID)
            } else {
                putString(KEY_SELECTED_ROUTE_ID, routeId)
            }
            apply()
        }
    }

    /**
     * Advances to the next configured route and stores it as selected.
     * Returns the newly selected route, or null if no routes are configured.
     */
    fun cycleToNextRoute(): OdPair? {
        val pairs = getOdPairs()
        if (pairs.isEmpty()) {
            setSelectedRouteId(null)
            return null
        }

        val currentId = getSelectedRouteId()
        val currentIndex = pairs.indexOfFirst { it.id == currentId }
        val nextIndex = if (currentIndex >= 0) {
            (currentIndex + 1) % pairs.size
        } else {
            0
        }

        val next = pairs[nextIndex]
        setSelectedRouteId(next.id)
        return next
    }

    // ── Notification dismissal tracking ───────────────────────────────────

    /** Check if user has dismissed notifications for this route. */
    fun isNotificationDismissedByUser(pairId: String): Boolean {
        val dismissedRouteId = prefs.getString(KEY_DISMISSED_ROUTE_ID, null)
        val legacyDismissed = prefs.getBoolean(KEY_DISMISSED_PREFIX + pairId, false)
        return dismissedRouteId == pairId || legacyDismissed
    }

    /** Mark notification as dismissed by user for this route. */
    fun setNotificationDismissedByUser(pairId: String, dismissed: Boolean) {
        prefs.edit().apply {
            if (dismissed) {
                putString(KEY_DISMISSED_ROUTE_ID, pairId)
            } else {
                remove(KEY_DISMISSED_ROUTE_ID)
            }
            putBoolean(KEY_DISMISSED_PREFIX + pairId, dismissed)
            apply()
        }
    }

    /** Clear dismissal flag for this route (called when active window ends). */
    fun clearNotificationDismissal(pairId: String) {
        prefs.edit().apply {
            if (prefs.getString(KEY_DISMISSED_ROUTE_ID, null) == pairId) {
                remove(KEY_DISMISSED_ROUTE_ID)
            }
            remove(KEY_DISMISSED_PREFIX + pairId)
            apply()
        }
    }

    /** Returns route id that is currently dismissed, if any. */
    fun getDismissedNotificationRouteId(): String? {
        return prefs.getString(KEY_DISMISSED_ROUTE_ID, null)
    }

    /** Clears any current notification dismissal regardless of route id. */
    fun clearNotificationDismissal() {
        prefs.edit().remove(KEY_DISMISSED_ROUTE_ID).apply()
    }

    /** Last route id seen in an active window, or null when no route is active. */
    fun getLastActiveRouteId(): String? {
        return prefs.getString(KEY_LAST_ACTIVE_ROUTE_ID, null)
    }

    /** Persist current active route id (null means no active route right now). */
    fun setLastActiveRouteId(routeId: String?) {
        prefs.edit().apply {
            if (routeId == null) {
                remove(KEY_LAST_ACTIVE_ROUTE_ID)
            } else {
                putString(KEY_LAST_ACTIVE_ROUTE_ID, routeId)
            }
            apply()
        }
    }

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
        val activeDays: List<Int>? = null,
        val directionId: Int,
        val notificationsEnabled: Boolean = true
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
            activeDays = activeDays?.toSet() ?: (1..7).toSet(),
            directionId = directionId,
            notificationsEnabled = notificationsEnabled
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
                activeDays = p.activeDays.toList(),
                directionId = p.directionId,
                notificationsEnabled = p.notificationsEnabled
            )
        }
    }

    companion object {
        const val DEFAULT_SERVER_URL = "http://frankipi:5050"
        fun newId(): String = UUID.randomUUID().toString()

        private const val LEGACY_FAIRFIELD_STOP_ID = 1066
        private const val FAIRFIELD_STOP_ID = 1065
        private const val LEGACY_HURSTBRIDGE_STOP_ID = 1093
        private const val HURSTBRIDGE_STOP_ID = 1100
    }

    private fun migrateLegacyStopIds(pair: OdPair): OdPair {
        val correctedOriginStopId =
            if (pair.originName == "Fairfield" && pair.originStopId == LEGACY_FAIRFIELD_STOP_ID) {
                FAIRFIELD_STOP_ID
            } else if (
                pair.originName == "Hurstbridge" &&
                pair.originStopId == LEGACY_HURSTBRIDGE_STOP_ID
            ) {
                HURSTBRIDGE_STOP_ID
            } else {
                pair.originStopId
            }

        val correctedDestinationStopId =
            if (pair.destinationName == "Fairfield" && pair.destinationStopId == LEGACY_FAIRFIELD_STOP_ID) {
                FAIRFIELD_STOP_ID
            } else if (
                pair.destinationName == "Hurstbridge" &&
                pair.destinationStopId == LEGACY_HURSTBRIDGE_STOP_ID
            ) {
                HURSTBRIDGE_STOP_ID
            } else {
                pair.destinationStopId
            }

        return if (
            correctedOriginStopId == pair.originStopId &&
            correctedDestinationStopId == pair.destinationStopId
        ) {
            pair
        } else {
            pair.copy(
                originStopId = correctedOriginStopId,
                destinationStopId = correctedDestinationStopId,
            )
        }
    }
}
