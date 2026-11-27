package com.nightstand

import android.content.Context
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/**
 * "New version available": the newest GitHub release tagged vX.Y[.Z] with an
 * .apk attached, if newer than the installed versionName. Checked at most
 * once a day from Settings → Home. Release tags only (the model release
 * `model-mobileclip-s0-v1` is ignored).
 */
object UpdateCheck {
    const val RELEASES = "https://api.github.com/repos/isakhnov/NightStand/releases?per_page=20"
    private const val DAY = 24 * 3_600_000L

    data class Update(val version: String, val apkUrl: String, val pageUrl: String)

    private fun sp(c: Context) = c.getSharedPreferences("update", Context.MODE_PRIVATE)

    /** Pure: "1.2" vs "1.10.1" etc. Non-numeric parts (e.g. "1.1-beta7") count as older than the release. */
    fun isNewer(candidate: String, installed: String): Boolean {
        fun parts(v: String): List<Int> = v.removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val a = parts(candidate); val b = parts(installed)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return installed.contains('-') && !candidate.contains('-') // 1.1 is newer than 1.1-beta7
    }

    /** Pure: pick the newest app release (tag vX.Y…, not draft/prerelease, with an .apk). */
    fun pick(json: String, installed: String): Update? {
        val arr = JSONArray(json)
        var best: Update? = null
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            val tag = r.optString("tag_name")
            if (r.optBoolean("draft") || r.optBoolean("prerelease") || !Regex("""v\d+(\.\d+)*""").matches(tag)) continue
            val assets = r.optJSONArray("assets") ?: continue
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk") }?.optString("browser_download_url") ?: continue
            val v = tag.removePrefix("v")
            if (isNewer(v, installed) && (best == null || isNewer(v, best.version))) best = Update(v, apk, r.optString("html_url"))
        }
        return best
    }

    fun cached(c: Context): Update? {
        val s = sp(c)
        val v = s.getString("version", null) ?: return null
        return Update(v, s.getString("apk", "")!!, s.getString("page", "")!!)
    }

    /** Off the main thread. Returns the update (also cached) or null. */
    fun check(c: Context, installed: String, force: Boolean = false): Update? {
        val s = sp(c)
        if (!force && System.currentTimeMillis() - s.getLong("checkedAt", 0) < DAY) {
            return cached(c)?.takeIf { isNewer(it.version, installed) }
        }
        val conn = (URL(RELEASES).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        return try {
            val u = pick(conn.inputStream.bufferedReader().readText(), installed)
            s.edit().putLong("checkedAt", System.currentTimeMillis()).apply {
                if (u == null) remove("version") else putString("version", u.version).putString("apk", u.apkUrl).putString("page", u.pageUrl)
            }.apply()
            u
        } catch (t: Throwable) {
            WatcherService.log("update check failed: $t"); null
        } finally { conn.disconnect() }
    }
}
