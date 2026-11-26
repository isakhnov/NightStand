package com.nightstand

import android.content.Context

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("nightstand", Context.MODE_PRIVATE)

    fun enabled(c: Context) = sp(c).getBoolean("enabled", true)
    fun setEnabled(c: Context, v: Boolean) = sp(c).edit().putBoolean("enabled", v).apply()

    /** Show notification titles on the locked screen (iPhone hides them until Face ID). */
    fun showContent(c: Context) = sp(c).getBoolean("showContent", false)
    fun setShowContent(c: Context, v: Boolean) = sp(c).edit().putBoolean("showContent", v).apply()

    enum class ClockStyle { DIGITAL, ANALOG }

    fun clockStyle(c: Context) =
        runCatching { ClockStyle.valueOf(sp(c).getString("clockStyle", null)!!) }.getOrDefault(ClockStyle.ANALOG)
    fun setClockStyle(c: Context, v: ClockStyle) = sp(c).edit().putString("clockStyle", v.name).apply()

    /**
     * Window brightness in night mode, 0..1. First test: 0.01 was far too dark
     * next to an iPhone; 0.20 is the new starting point, tunable by swiping
     * up/down on the display or with the slider on the setup screen.
     */
    fun nightBrightness(c: Context) = sp(c).getFloat("nightBrightness", 0.20f)
    fun setNightBrightness(c: Context, v: Float) =
        sp(c).edit().putFloat("nightBrightness", v.coerceIn(MIN_NIGHT, 1f)).apply()
    const val MIN_NIGHT = 0.01f

    /** Pages switched off by the user (ids from PageRegistry). */
    fun disabledPages(c: Context): Set<String> = sp(c).getStringSet("disabledPages", emptySet())!!
    fun setPageEnabled(c: Context, id: String, on: Boolean) {
        val s = disabledPages(c).toMutableSet()
        if (on) s.remove(id) else s.add(id)
        sp(c).edit().putStringSet("disabledPages", s).apply()
    }

    /** Page shown last; Nightstand reopens on it. */
    fun lastPage(c: Context) = sp(c).getString("lastPage", null)
    fun setLastPage(c: Context, id: String) = sp(c).edit().putString("lastPage", id).apply()

    /** Temperature unit: °F by default (user's choice), °C optional. */
    fun fahrenheit(c: Context) = sp(c).getBoolean("fahrenheit", true)
    fun setFahrenheit(c: Context, v: Boolean) = sp(c).edit().putBoolean("fahrenheit", v).apply()

    fun weatherCache(c: Context) = sp(c).getString("weatherCache", null)
    fun setWeatherCache(c: Context, v: String) = sp(c).edit().putString("weatherCache", v).apply()

    enum class PhotoSource { ALL, FAVORITES, ALBUM }
    fun photoSource(c: Context) =
        runCatching { PhotoSource.valueOf(sp(c).getString("photoSource", null)!!) }.getOrDefault(PhotoSource.ALL)
    fun setPhotoSource(c: Context, v: PhotoSource) = sp(c).edit().putString("photoSource", v.name).apply()
    /** MediaStore BUCKET_ID + display name of the chosen album. */
    fun photoAlbum(c: Context): Pair<String, String>? {
        val id = sp(c).getString("photoAlbumId", null) ?: return null
        return id to (sp(c).getString("photoAlbumName", null) ?: "Album")
    }
    fun setPhotoAlbum(c: Context, id: String, name: String) =
        sp(c).edit().putString("photoAlbumId", id).putString("photoAlbumName", name).apply()

    // --- Look ---------------------------------------------------------------
    fun nightColor(c: Context) =
        runCatching { NightColor.valueOf(sp(c).getString("nightColor", null)!!) }.getOrDefault(NightColor.PURE)
    fun setNightColor(c: Context, v: NightColor) = sp(c).edit().putString("nightColor", v.name).apply()

    fun secondHand(c: Context) = sp(c).getBoolean("secondHand", true)
    fun setSecondHand(c: Context, v: Boolean) = sp(c).edit().putBoolean("secondHand", v).apply()

    /** How dark the room must be for red mode: enter below / leave above (lux). */
    enum class Darkness(val label: String, val enterLux: Float, val exitLux: Float) {
        VERY_DARK("Very dark", 1f, 6f), DARK("Dark", 3f, 15f), DIM("Dim", 10f, 40f)
    }
    fun darkness(c: Context) =
        runCatching { Darkness.valueOf(sp(c).getString("darkness", null)!!) }.getOrDefault(Darkness.DARK)
    fun setDarkness(c: Context, v: Darkness) = sp(c).edit().putString("darkness", v.name).apply()

    // --- Home: which view opens first ---------------------------------------
    /** null = last used; otherwise a page id. */
    fun startPage(c: Context): String? = sp(c).getString("startPage", null)
    fun setStartPage(c: Context, id: String?) = sp(c).edit().putString("startPage", id).apply()

    // --- Views ----------------------------------------------------------------
    fun pageOrder(c: Context): List<String> =
        sp(c).getString("pageOrder", null)?.split(',')?.filter { it.isNotBlank() } ?: emptyList()
    fun setPageOrder(c: Context, ids: List<String>) = sp(c).edit().putString("pageOrder", ids.joinToString(",")).apply()

    /** Google calendars the user switched off (ids); default shows all. */
    fun calendarsOff(c: Context): Set<String> = sp(c).getStringSet("calendarsOff", emptySet())!!
    fun setCalendarOn(c: Context, id: Long, on: Boolean) {
        val s = calendarsOff(c).toMutableSet()
        if (on) s.remove(id.toString()) else s.add(id.toString())
        sp(c).edit().putStringSet("calendarsOff", s).apply()
    }

    // --- Setup ------------------------------------------------------------------
    fun showDebug(c: Context) = sp(c).getBoolean("showDebug", false)
    fun setShowDebug(c: Context, v: Boolean) = sp(c).edit().putBoolean("showDebug", v).apply()

    // --- Alarms & timers (Look tab) ---------------------------------------------
    fun showAlarm(c: Context) = sp(c).getBoolean("showAlarm", true)
    fun setShowAlarm(c: Context, v: Boolean) = sp(c).edit().putBoolean("showAlarm", v).apply()
    fun showTimers(c: Context) = sp(c).getBoolean("showTimers", true)
    fun setShowTimers(c: Context, v: Boolean) = sp(c).edit().putBoolean("showTimers", v).apply()
    /** Big countdown replaces the right-hand view in a timer's last minute. */
    fun bigCountdownDay(c: Context) = sp(c).getBoolean("bigCountdownDay", true)
    fun setBigCountdownDay(c: Context, v: Boolean) = sp(c).edit().putBoolean("bigCountdownDay", v).apply()
    fun bigCountdownNight(c: Context) = sp(c).getBoolean("bigCountdownNight", false)
    fun setBigCountdownNight(c: Context, v: Boolean) = sp(c).edit().putBoolean("bigCountdownNight", v).apply()

    /**
     * UNDER DEVELOPMENT — placeholder only. Would allow the "next alarm" to
     * come from non-clock apps (Calendar, Routines, Tasker). Shown disabled in
     * Look; Alarms.next() ignores it and always uses clock apps only.
     */
    fun alarmsFromOtherApps(c: Context) = sp(c).getBoolean("alarmsFromOtherApps", false)

    /** Show learned clock alarms as "expected" while a Routines/Bedtime entry hides them. */
    fun rememberAlarms(c: Context) = sp(c).getBoolean("rememberAlarms", true)
    fun setRememberAlarms(c: Context, v: Boolean) = sp(c).edit().putBoolean("rememberAlarms", v).apply()

    /** Power: at night the face redraws once a minute unless this is on. */
    fun secondHandAtNight(c: Context) = sp(c).getBoolean("secondHandAtNight", false)
    fun setSecondHandAtNight(c: Context, v: Boolean) = sp(c).edit().putBoolean("secondHandAtNight", v).apply()

    // --- Photos: Smart selection (docs/photos-intelligence/step-a) ---------------
    /** Smart selection on: needs the downloaded model; otherwise photos stay random. */
    fun photoSmart(c: Context) = sp(c).getBoolean("photoSmart", false)
    fun setPhotoSmart(c: Context, v: Boolean) = sp(c).edit().putBoolean("photoSmart", v).apply()

    /**
     * How alike a photo must be to one you swiped away to be hidden too (cosine of
     * MobileCLIP embeddings). Validation: edits of one shot (shift, JPEG, rotate,
     * blur, re-colour) 0.975–0.999; different scenes 0.11–0.50. Strict only takes
     * near-identical shots; Wide also takes the same subject from another angle.
     */
    enum class LookAlike(val label: String, val cos: Float) {
        STRICT("Strict", 0.95f), NORMAL("Normal", 0.92f), WIDE("Wide", 0.88f)
    }
    fun photoLookAlike(c: Context) =
        runCatching { LookAlike.valueOf(sp(c).getString("photoLookAlike", null)!!) }.getOrDefault(LookAlike.NORMAL)
    fun setPhotoLookAlike(c: Context, v: LookAlike) = sp(c).edit().putString("photoLookAlike", v.name).apply()

    /** Fold bursts (photos seconds apart that look alike) to their best shot. */
    fun photoOnePerBurst(c: Context) = sp(c).getBoolean("photoOnePerBurst", true)
    fun setPhotoOnePerBurst(c: Context, v: Boolean) = sp(c).edit().putBoolean("photoOnePerBurst", v).apply()

    /** Times of day ("HH:mm") whose ownerless alarms are Routines/Bedtime, not the user's alarms. */
    fun hiddenAlarmTimes(c: Context): Set<String> = sp(c).getStringSet("hiddenAlarmTimes", emptySet())!!
    fun setAlarmTimeHidden(c: Context, key: String, hide: Boolean) {
        val s = hiddenAlarmTimes(c).toMutableSet()
        if (hide) s.add(key) else s.remove(key)
        sp(c).edit().putStringSet("hiddenAlarmTimes", s).apply()
    }
}
