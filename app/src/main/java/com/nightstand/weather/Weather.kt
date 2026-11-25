package com.nightstand.weather

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.nightstand.Prefs
import com.nightstand.WatcherService
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

data class WeatherNow(
    val place: String?,
    val temp: Int,
    val code: Int,
    val isDay: Boolean,
    val high: Int,
    val low: Int,
    val fahrenheit: Boolean,
    val fetchedAt: Long,
)

/**
 * Current weather for the phone's location from Open-Meteo (no API key; free
 * for non-commercial use, attribution in README). Fetched only while the
 * weather page is on screen: coarse last-known location (no background
 * location), reverse-geocoded to a place name, cached so the page has
 * something to show instantly and offline.
 */
object Weather {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private const val MAX_AGE_MS = 30 * 60_000L

    @Volatile var current: WeatherNow? = null
        internal set // tests/previews
    @Volatile private var inFlight = false

    fun hasLocationPermission(c: Context) =
        c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Debug: make the next refresh fetch regardless of age. */
    fun invalidate() { current = current?.copy(fetchedAt = 0) }

    fun loadCache(c: Context) {
        if (current != null) return
        current = runCatching { parse(JSONObject(Prefs.weatherCache(c) ?: return)) }.getOrNull()
    }

    /** Refresh if stale (or unit changed); [onDone] runs on the main thread. */
    fun refreshIfStale(c: Context, onDone: () -> Unit) {
        val f = Prefs.fahrenheit(c)
        val cur = current
        if (inFlight) return
        if (cur != null && cur.fahrenheit == f && System.currentTimeMillis() - cur.fetchedAt < MAX_AGE_MS) return
        if (!hasLocationPermission(c)) return
        val app = c.applicationContext
        val loc = lastLocation(app)
        if (loc == null) { WatcherService.log("weather: no location yet"); return }
        inFlight = true
        io.execute {
            try {
                val w = fetch(loc.latitude, loc.longitude, f, placeName(app, loc))
                current = w
                Prefs.setWeatherCache(app, toJson(w).toString())
                WatcherService.log("weather: ${w.temp}° ${WeatherCodes.label(w.code)} @ ${w.place}")
            } catch (t: Throwable) {
                WatcherService.log("weather failed: $t")
            } finally {
                inFlight = false
                main.post(onDone)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun lastLocation(c: Context): Location? {
        val lm = c.getSystemService(LocationManager::class.java)
        val providers = listOfNotNull(
            if (Build.VERSION.SDK_INT >= 31) LocationManager.FUSED_PROVIDER else null,
            LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER, LocationManager.GPS_PROVIDER,
        )
        val best = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        if (best == null) {
            // Nothing cached yet (fresh boot): ask once; the next refresh will find it.
            runCatching {
                lm.getCurrentLocation(LocationManager.NETWORK_PROVIDER, null, c.mainExecutor) { }
            }
        }
        return best
    }

    @Suppress("DEPRECATION")
    private fun placeName(c: Context, loc: Location): String? = runCatching {
        if (!Geocoder.isPresent()) return null
        val a = Geocoder(c).getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull() ?: return null
        a.locality ?: a.subAdminArea ?: a.adminArea
    }.getOrNull()

    private fun fetch(lat: Double, lon: Double, f: Boolean, place: String?): WeatherNow {
        val url = URL(
            "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f".format(java.util.Locale.US, lat, lon) +
                "&current=temperature_2m,weather_code,is_day" +
                "&daily=temperature_2m_max,temperature_2m_min&forecast_days=1&timezone=auto" +
                "&temperature_unit=" + if (f) "fahrenheit" else "celsius"
        )
        val conn = (url.openConnection() as HttpURLConnection).apply { connectTimeout = 10_000; readTimeout = 10_000 }
        try {
            val body = conn.inputStream.bufferedReader().readText()
            return parseOpenMeteo(JSONObject(body), f, place, System.currentTimeMillis())
        } finally { conn.disconnect() }
    }

    fun parseOpenMeteo(j: JSONObject, f: Boolean, place: String?, now: Long): WeatherNow {
        val cur = j.getJSONObject("current")
        val daily = j.getJSONObject("daily")
        return WeatherNow(
            place = place,
            temp = Math.round(cur.getDouble("temperature_2m")).toInt(),
            code = cur.getInt("weather_code"),
            isDay = cur.optInt("is_day", 1) == 1,
            high = Math.round(daily.getJSONArray("temperature_2m_max").getDouble(0)).toInt(),
            low = Math.round(daily.getJSONArray("temperature_2m_min").getDouble(0)).toInt(),
            fahrenheit = f,
            fetchedAt = now,
        )
    }

    private fun toJson(w: WeatherNow) = JSONObject().apply {
        put("place", w.place ?: JSONObject.NULL); put("temp", w.temp); put("code", w.code)
        put("isDay", w.isDay); put("high", w.high); put("low", w.low)
        put("f", w.fahrenheit); put("at", w.fetchedAt)
    }

    private fun parse(j: JSONObject) = WeatherNow(
        if (j.isNull("place")) null else j.getString("place"), j.getInt("temp"), j.getInt("code"),
        j.getBoolean("isDay"), j.getInt("high"), j.getInt("low"), j.getBoolean("f"), j.getLong("at"),
    )
}
