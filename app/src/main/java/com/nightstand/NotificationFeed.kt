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
        timerReport = if (clock.isEmpty()) emptyList()
            else clock.map { com.nightstand.clock.TimerParser.explain(fields(it), now) }
        clockDump = clock.joinToString("\n\n") { dump(it) }
        val own = packageName
        messages = com.nightstand.messages.MessageRules.summarize(all.map { sbn ->
            val n = sbn.notification
            val isConv = android.os.Build.VERSION.SDK_INT >= 31 && ranking != null && ranking.getRanking(sbn.key, tmp) && tmp.isConversation
            com.nightstand.messages.MessageRules.Fields(
                pkg = sbn.packageName, ongoing = sbn.isOngoing,
                groupSummary = n.flags and Notification.FLAG_GROUP_SUMMARY != 0,
                category = n.category, template = n.extras.getString(Notification.EXTRA_TEMPLATE),
                isConversation = isConv, number = n.number,
                styleMessages = (n.extras.getParcelableArray(Notification.EXTRA_MESSAGES)?.size) ?: 0,
                postTime = sbn.postTime, smallIcon = n.smallIcon,
            )
        }, own)
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
        val template = sbn.notification.extras.getString(Notification.EXTRA_TEMPLATE)
        val mt = if (sbn.packageName in com.nightstand.clock.ClockApps.PACKAGES &&
            template?.endsWith("MetricStyle") == true) metricTimer(sbn) else null
        return com.nightstand.clock.TimerParser.Fields(
            pkg = sbn.packageName,
            metricTimer = mt != null,
            metricEndsAt = mt?.endsAt,
            metricPausedMs = mt?.pausedMs,
            metricLabel = mt?.label,
            template = template,
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
        /** Messaging apps with unread notifications (icon + count; Look → Messages). */
        @Volatile var messages: List<com.nightstand.messages.MessageApp> = emptyList()
            private set
        @Volatile var timers: List<com.nightstand.clock.RunningTimer> = emptyList()
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
