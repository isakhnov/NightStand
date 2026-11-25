package com.nightstand.ui

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.nightstand.CalendarSource
import com.nightstand.NotificationFeed
import com.nightstand.weather.Weather

/** One row of the Setup tab. [fix] either asks for a runtime permission or opens a system page. */
data class Perm(
    val title: String,
    val sub: String,
    val required: Boolean,
    val granted: Boolean,
    val fixLabel: String = "Fix",
    val runtime: String? = null,
    val settingsIntent: Intent? = null,
    /** Shown instead of [sub] while not granted. */
    val problem: String? = null,
)

object Permissions {
    fun list(c: Context): List<Perm> {
        val pkg = Uri.parse("package:${c.packageName}")
        val nm = c.getSystemService(NotificationManager::class.java)
        val pm = c.getSystemService(PowerManager::class.java)
        fun has(p: String) = c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        val photosFull = if (Build.VERSION.SDK_INT >= 33) has(Manifest.permission.READ_MEDIA_IMAGES)
            else has(Manifest.permission.READ_EXTERNAL_STORAGE)
        val photosPartial = Build.VERSION.SDK_INT >= 34 && has(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)

        return listOf(
            Perm("Full-screen alerts", "Appear over the lock screen", true,
                Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent(),
                settingsIntent = if (Build.VERSION.SDK_INT >= 34)
                    Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg) else null),
            Perm("Display over other apps", "Open directly from the charger", true,
                Settings.canDrawOverlays(c),
                settingsIntent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg)),
            Perm("Battery: unrestricted", "Keeps the watcher running overnight", true,
                pm.isIgnoringBatteryOptimizations(c.packageName),
                settingsIntent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg),
                problem = "One UI stops the watcher otherwise"),
            Perm("Notifications", "Watcher and launch alert", true,
                Build.VERSION.SDK_INT < 33 || has(Manifest.permission.POST_NOTIFICATIONS),
                runtime = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.POST_NOTIFICATIONS else null),
            Perm("Calendar", "Up next · Google Calendar", false, CalendarSource.isGranted(c),
                runtime = Manifest.permission.READ_CALENDAR),
            Perm("Location", "Weather · approximate", false, Weather.hasLocationPermission(c),
                runtime = Manifest.permission.ACCESS_COARSE_LOCATION),
            Perm("Photos", "Photos on this phone", false, photosFull,
                fixLabel = if (photosPartial) "Allow all" else "Fix",
                runtime = if (photosPartial) null else
                    if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE,
                // Upgrading partial → full access is done on the app's permission page.
                settingsIntent = if (photosPartial) Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg) else null,
                problem = if (photosPartial) "Only some photos allowed" else null),
            Perm("Notification access", "Notifications strip · Now playing", false, NotificationFeed.isGranted(c),
                settingsIntent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)),
        )
    }
}
