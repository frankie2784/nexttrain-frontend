package com.trainwidget.api

import android.util.Log
import com.google.gson.Gson
import com.trainwidget.data.Departure
import com.trainwidget.data.PtvDeparture
import com.trainwidget.data.PtvDeparturesResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Url
import java.net.URL
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private const val TAG = "PtvApi"
private const val BASE_URL = "https://timetableapi.ptv.vic.gov.au"
private const val ROUTE_TYPE_TRAIN = 0
private const val MAX_DEPARTURES = 10   // fetch extra for filtering

// ── Retrofit interface ──────────────────────────────────────────────────────

interface PtvRetrofitService {
    @GET
    suspend fun getDepartures(@Url signedUrl: String): PtvDeparturesResponse
}

// ── RPi4 server response models ─────────────────────────────────────────────

data class ServerDeparturesResponse(
    val stop_id: String,
    val departures: List<ServerDeparture>,
    val timestamp: String
)

data class ServerDeparture(
    val trip_id: String?,
    val route_id: String?,
    val direction_id: String?,
    val scheduled_time: String,
    val estimated_time: String?,
    val delay_minutes: Int,
    val delay_seconds: Int?,
    val minutes_until: Long,
    val trip_headsign: String?,
    val platform: String?
)

// ── API Client ──────────────────────────────────────────────────────────────

/**
 * PTV Timetable API v3 client.
 *
 * Can operate in two modes:
 *   1. Direct PTV API (HMAC-signed requests) — legacy / fallback.
 *   2. RPi4 server mode — all GTFS processing runs on the server;
 *      the widget just calls GET /departures.
 */
class PtvApiClient(
    private val devId: String,
    private val apiKey: String
) {

    private val retrofit: Retrofit by lazy {
        val logging = HttpLoggingInterceptor { Log.d(TAG, it) }.apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()

        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    private val service: PtvRetrofitService by lazy {
        retrofit.create(PtvRetrofitService::class.java)
    }

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Fetch the next [maxResults] departures from [stopId] on the train network.
     * Returns an empty list on error rather than throwing (widget should be fault-tolerant).
     *
     * @param stopId       PTV stop ID of the origin station
     * @param directionId  Optional direction filter (-1 = no filter); use to limit to services
     *                     heading toward the destination direction.
     * @param maxResults   Maximum departures to return from the API (then we filter further).
     */
    suspend fun getDepartures(
        stopId: Int,
        directionId: Int = -1,
        maxResults: Int = MAX_DEPARTURES
    ): List<PtvDeparture> = withContext(Dispatchers.IO) {
        try {
            val path = buildPath(stopId, directionId, maxResults)
            val signedUrl = buildSignedUrl(path)
            val response = service.getDepartures(signedUrl)
            response.departures
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch departures for stop $stopId", e)
            emptyList()
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private fun buildPath(stopId: Int, directionId: Int, maxResults: Int): String {
        val sb = StringBuilder("/v3/departures/route_type/$ROUTE_TYPE_TRAIN/stop/$stopId")
        sb.append("?max_results=$maxResults")
        sb.append("&expand=run,stop,route")
        sb.append("&include_cancelled=false")
        if (directionId >= 0) sb.append("&direction_id=$directionId")
        return sb.toString()
    }

    /**
     * Signs [path] with HMAC-SHA1 and returns the full signed URL string.
     */
    private fun buildSignedUrl(path: String): String {
        // Append devid
        val separator = if ('?' in path) "&" else "?"
        val pathWithDev = "$path${separator}devid=$devId"

        // HMAC-SHA1
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(apiKey.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        val signature = mac.doFinal(pathWithDev.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02X".format(it) }

        return "$BASE_URL$pathWithDev&signature=$signature"
    }

    // ── RPi4 server mode ────────────────────────────────────────────────

    /**
     * Fetch departures from the RPi4 GTFS server instead of the PTV API directly.
     * The server merges static schedule + real-time delays and returns
     * pre-processed Departure objects.
     */
    suspend fun getDeparturesFromServer(
        serverUrl: String,
        stopId: Int,
        destinationStopId: Int? = null,
        directionId: Int = -1,
        maxResults: Int = 5
    ): List<Departure> = withContext(Dispatchers.IO) {
        try {
            val sb = StringBuilder("$serverUrl/departures?stop_id=$stopId")
            if (destinationStopId != null) sb.append("&destination_stop_id=$destinationStopId")
            sb.append("&max_results=$maxResults")
            if (directionId >= 0) sb.append("&direction_id=$directionId")
            val url = URL(sb.toString())
            val json = url.readText()
            val response = Gson().fromJson(json, ServerDeparturesResponse::class.java)
            response.departures.map { dep ->
                Departure(
                    scheduledTime = dep.scheduled_time,
                    estimatedTime = dep.estimated_time,
                    delayMinutes = dep.delay_minutes,
                    platformNumber = dep.platform,
                    minutesUntilDeparture = dep.minutes_until
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch departures from server $serverUrl", e)
            emptyList()
        }
    }

    suspend fun isServerReachable(serverUrl: String): Boolean = withContext(Dispatchers.IO) {
        try {
            URL("$serverUrl/health").readText()
            true
        } catch (_: Exception) {
            false
        }
    }
}
