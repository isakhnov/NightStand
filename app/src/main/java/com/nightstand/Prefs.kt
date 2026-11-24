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
}
