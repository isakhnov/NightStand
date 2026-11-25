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

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap?, reason: Int) {
        // The clock app withdrawing its own alarm notification means the alarm
        // was dismissed early or has rung; the user swiping it away means nothing.
        if (sbn.packageName in com.nightstand.clock.ClockApps.PACKAGES &&
            (reason == REASON_APP_CANCEL || reason == REASON_APP_CANCEL_ALL)
        ) {
            val f = fields(sbn)
            val a = com.nightstand.clock.AlarmNotificationParser.parse(
                f.pkg, f.title, f.text, System.currentTimeMillis(), android.text.format.DateFormat.is24HourFormat(this))
            if (a != null) com.nightstand.clock.AlarmMemory.forget(this, "clock app withdrew its alarm notification")
        }
        rebuild()
    }

    private fun rebuild() {
        val all = try { activeNotifications ?: emptyArray() } catch (_: Throwable) { emptyArray() }
        val ranking = currentRanking
        val tmp = Ranking()
        val pm = packageManager
        val now = System.currentTimeMillis()
        val clock = all.filter { it.packageName in com.nightstand.clock.ClockApps.PACKAGES }
        timers = clock.mapNotNull { com.nightstand.clock.TimerParser.parse(fields(it), now) }
        timerReport = if (clock.isEmpty()) emptyList()
            else clock.map { com.nightstand.clock.TimerParser.explain(fields(it), now) }
        val is24 = android.text.format.DateFormat.is24HourFormat(this)
        notifiedAlarm = clock.mapNotNull {
            val f = fields(it)
            com.nightstand.clock.AlarmNotificationParser.parse(f.pkg, f.title, f.text, now, is24)
        }.minByOrNull { it.triggerAt }
        notifiedAlarm?.let {
            com.nightstand.clock.AlarmMemory.remember(this, it)
            StandByState.notifyAlarmChanged()
        }
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

    private data class Custom(val endsAt: Long?, val countDown: Boolean, val texts: List<String>)

    /**
     * Inflate the notification's custom layout in our process and read its
     * Chronometer. Chronometer.base is on the elapsedRealtime clock; the base
     * was set relative to when the notification was posted, so convert via
     * postTime. Only for clock apps; failures just mean "no custom data".
     */
    @Suppress("DEPRECATION")
    private fun custom(sbn: StatusBarNotification): Custom {
        val n = sbn.notification
        val rv = n.contentView ?: n.bigContentView ?: return Custom(null, false, emptyList())
        return try {
            val root = rv.apply(this, android.widget.FrameLayout(this))
            var end: Long? = null; var down = false
            val texts = mutableListOf<String>()
            fun walk(v: android.view.View) {
                when (v) {
                    is android.widget.Chronometer -> {
                        val nowWall = System.currentTimeMillis()
                        val nowElapsed = android.os.SystemClock.elapsedRealtime()
                        end = nowWall + (v.base - nowElapsed)
                        down = v.isCountDown
                    }
                    is android.widget.TextView -> v.text?.toString()?.takeIf { it.isNotBlank() }?.let { texts += it }
                }
                if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
            walk(root)
            Custom(end, down, texts.distinct())
        } catch (t: Throwable) {
            Custom(null, false, emptyList())
        }
    }

    private data class MetricTimer(val endsAt: Long?, val pausedMs: Long?, val label: String?)

    /**
     * Android 17 Notification.MetricStyle → first timer metric. Read by
     * reflection (public API, compileSdk stays 36): style.getMetrics() →
     * Metric.getValue() as TimeDifference → isTimer(), getZeroTime() /
     * getZeroElapsedRealtime() when running, getPausedDuration() when paused.
     */
    private fun metricTimer(sbn: StatusBarNotification): MetricTimer? = try {
        val style = Notification.Builder.recoverBuilder(this, sbn.notification).style
        if (style == null || style.javaClass.name != "android.app.Notification\$MetricStyle") null
        else {
            @Suppress("UNCHECKED_CAST")
            val metrics = style.javaClass.getMethod("getMetrics").invoke(style) as List<Any>
            metrics.firstNotNullOfOrNull { m ->
                val v = m.javaClass.getMethod("getValue").invoke(m) ?: return@firstNotNullOfOrNull null
                val vc = v.javaClass
                if (!vc.name.endsWith("TimeDifference") || vc.getMethod("isTimer").invoke(v) != true) return@firstNotNullOfOrNull null
                val label = m.javaClass.getMethod("getLabel").invoke(m)?.toString()
                val paused = vc.getMethod("getPausedDuration").invoke(v) as java.time.Duration?
                val zeroWall = (vc.getMethod("getZeroTime").invoke(v) as java.time.Instant?)?.toEpochMilli()
                    ?: (vc.getMethod("getZeroElapsedRealtime").invoke(v) as Long?)?.let {
                        System.currentTimeMillis() + (it - android.os.SystemClock.elapsedRealtime())
                    }
                MetricTimer(if (paused == null) zeroWall else null, paused?.toMillis(), label)
            }
        }
    } catch (t: Throwable) { null }

    private fun fields(sbn: StatusBarNotification): com.nightstand.clock.TimerParser.Fields {
        val n = sbn.notification
        val x = n.extras
        val mt = if (sbn.packageName in com.nightstand.clock.ClockApps.PACKAGES &&
            x.getString(Notification.EXTRA_TEMPLATE)?.endsWith("MetricStyle") == true) metricTimer(sbn) else null
        val cv = if (mt == null && sbn.packageName in com.nightstand.clock.ClockApps.PACKAGES &&
            !x.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)) custom(sbn) else Custom(null, false, emptyList())
        return com.nightstand.clock.TimerParser.Fields(
            pkg = sbn.packageName, ongoing = sbn.isOngoing, whenMs = n.`when`,
            showChronometer = x.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER),
            countDown = x.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN),
            title = x.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            text = x.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            actions = n.actions?.mapNotNull { it.title?.toString() } ?: emptyList(),
            category = n.category,
            customEndsAt = cv.endsAt,
            customCountDown = cv.countDown,
            customTexts = cv.texts,
            metricTimer = mt != null,
            metricEndsAt = mt?.endsAt,
            metricPausedMs = mt?.pausedMs,
            metricLabel = mt?.label,
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
            metricTimer(sbn)?.let { append("  metric timer: $it\n") }
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
        /** One line per clock-app notification: what was read from it, or why not. */
        @Volatile var timerReport: List<String> = emptyList()
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
