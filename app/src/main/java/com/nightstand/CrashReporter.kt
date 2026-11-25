package com.nightstand

import android.app.Application
import android.content.Context
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the last crash so it can be seen and shared from Settings → Home.
 * Without adb there is otherwise no way to know why the app closed; Android
 * often closes a crashing app silently, which looks like "it minimised".
 */
class NightstandApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
    }
}

object CrashReporter {
    private const val KEY = "lastCrash"
    private fun sp(c: Context) = c.getSharedPreferences("crash", Context.MODE_PRIVATE)

    fun install(app: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                val sw = StringWriter(); e.printStackTrace(PrintWriter(sw))
                val ver = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull()
                val head = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()) +
                    "  v$ver  thread=${thread.name}\n"
                // commit(), not apply(): the process is about to die.
                sp(app).edit().putString(KEY, head + sw.toString().take(12_000)).commit()
            }
            previous?.uncaughtException(thread, e)
        }
    }

    fun last(c: Context): String? = sp(c).getString(KEY, null)
    fun clear(c: Context) = sp(c).edit().remove(KEY).apply()
}
