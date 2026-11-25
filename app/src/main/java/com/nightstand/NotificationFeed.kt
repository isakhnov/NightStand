package com.nightstand

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** One app's worth of pending notifications, as Nightstand shows them. */
data class NotifSummary(
    val pkg: String,
    val appLabel: String,
    val count: Int,
    /** Latest title; null for VISIBILITY_SECRET. Shown only if the user opts in. */
    val title: String?,
    /** What the posting app allows on a lock screen (public / publicVersion). */
    val publicTitle: String?,
    val postTime: Long,
    /** The app's monochrome status-bar icon; tints cleanly to one colour. */
    val smallIcon: android.graphics.drawable.Icon? = null,
    /** Cache key for [smallIcon] (resource id, or 0). */
    val iconKey: Int = 0,
)

/**
 * Notification access — the same permission YTMLauncher already uses. Keeps a
 * grouped snapshot of what a person would care about on a nightstand: no
 * ongoing (music, navigation, our own watcher), no silent, no group summaries.
 */
class NotificationFeed : NotificationListenerService() {

    override fun onListenerConnected() { instance = this; rebuild() }
    override fun onListenerDisconnected() { instance = null; snapshot = emptyList(); StandByState.notifyChanged() }
    override fun onNotificationPosted(sbn: StatusBarNotification) = rebuild()
    override fun onNotificationRemoved(sbn: StatusBarNotification) = rebuild()

    private fun rebuild() {
        val all = try { activeNotifications ?: emptyArray() } catch (_: Throwable) { emptyArray() }
        val ranking = currentRanking
        val tmp = Ranking()
        val pm = packageManager
        val now = System.currentTimeMillis()
        val clock = all.filter { it.packageName in com.nightstand.clock.ClockApps.PACKAGES }
        timers = clock.mapNotNull { com.nightstand.clock.TimerParser.parse(fields(it), now) }
        val is24 = android.text.format.DateFormat.is24HourFormat(this)
        notifiedAlarm = clock.mapNotNull {
            val f = fields(it)
            com.nightstand.clock.AlarmNotificationParser.parse(f.pkg, f.title, f.text, now, is24)
        }.minByOrNull { it.triggerAt }
        clockDump = clock.joinToString("\n\n") { dump(it) }
        snapshot = all.asSequence()
            .filter { it.packageName != packageName }
            .filter { !it.isOngoing && it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
            .filter {
                // Silent / minimized notifications don't belong on a bedside display.
                ranking == null || !ranking.getRanking(it.key, tmp) ||
                    tmp.importance >= NotificationManager.IMPORTANCE_DEFAULT
            }
            .groupBy { it.packageName }
            .map { (pkg, list) ->
                val latest = list.maxBy { it.postTime }
                NotifSummary(
                    pkg = pkg,
                    appLabel = try {
                        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                    } catch (_: Throwable) { pkg },
                    count = list.size,
                    title = latest.notification.let { n ->
                        if (n.visibility == Notification.VISIBILITY_SECRET) null else titleOf(n)
                    },
                    publicTitle = latest.notification.let { n ->
                        when (n.visibility) {
                            Notification.VISIBILITY_PUBLIC -> titleOf(n)
                            Notification.VISIBILITY_PRIVATE -> n.publicVersion?.let { titleOf(it) }
                            else -> null
                        }
                    },
                    postTime = latest.postTime,
                    smallIcon = latest.notification.smallIcon,
                    iconKey = latest.notification.smallIcon?.let {
                        if (it.type == android.graphics.drawable.Icon.TYPE_RESOURCE) it.resId else it.hashCode()
                    } ?: 0,
                )
            }
            .sortedByDescending { it.postTime }
        StandByState.notifyChanged()
    }

    private fun fields(sbn: StatusBarNotification): com.nightstand.clock.TimerParser.Fields {
        val n = sbn.notification
        val x = n.extras
        return com.nightstand.clock.TimerParser.Fields(
            pkg = sbn.packageName, ongoing = sbn.isOngoing, whenMs = n.`when`,
            showChronometer = x.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER),
            countDown = x.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN),
            title = x.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            text = x.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            actions = n.actions?.mapNotNull { it.title?.toString() } ?: emptyList(),
            category = n.category,
        )
    }

    /** Debug: everything a clock notification carries, to finalise the parser on-device. */
    private fun dump(sbn: StatusBarNotification): String {
        val n = sbn.notification
        val x = n.extras
        return buildString {
            append("pkg=${sbn.packageName} id=${sbn.id} ongoing=${sbn.isOngoing} channel=${n.channelId} ")
            append("category=${n.category} when=${n.`when`} (now=${System.currentTimeMillis()})\n")
            append("style=${x.getString(Notification.EXTRA_TEMPLATE)}\n")
            x.keySet().sorted().forEach { k -> append("  $k = ${runCatching { x.get(k) }.getOrNull()}\n") }
            n.actions?.forEach { append("  action: ${it.title}\n") }
        }
    }

    private fun titleOf(n: Notification): String? =
        n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()

    companion object {
        @Volatile var snapshot: List<NotifSummary> = emptyList()
            private set
        @Volatile var timers: List<com.nightstand.clock.RunningTimer> = emptyList()
            private set
        /** Alarm time from a clock app's own "Upcoming alarm"/"Snoozed" notification. */
        @Volatile var notifiedAlarm: com.nightstand.clock.NextAlarm? = null
            private set
        /** Debug → "Capture clock notifications". */
        @Volatile var clockDump: String = ""
            private set
        @Volatile private var instance: NotificationFeed? = null

        fun isGranted(c: Context): Boolean {
            val flat = Settings.Secure.getString(c.contentResolver, "enabled_notification_listeners") ?: return false
            val me = ComponentName(c, NotificationFeed::class.java).flattenToString()
            return flat.split(':').any { it == me }
        }
    }
}
