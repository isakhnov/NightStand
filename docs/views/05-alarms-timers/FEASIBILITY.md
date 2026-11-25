# 05: Alarms and timers on the clock faces

**Verdict: feasible, with one hard limit and one on-device unknown. No new permissions.**

- **Next alarm: yes, reliably.** `AlarmManager.getNextAlarmClock()` is public and needs no
  permission. It returns only the *single next* alarm, not every enabled one.
- **All enabled alarms: no.** No public API lists them. Samsung Clock's alarm database is
  not readable by a third-party app (details in §1.3).
- **Running timers: yes, through the Notification Listener access the app already has.**
  Clock apps post an ongoing notification per running timer. On Android 17 (AOSP DeskClock
  already does this) it is a `MetricStyle` notification whose `TimeDifference` gives the
  exact end time or the paused remainder. **Unknown:** exactly what *Samsung* Clock puts in
  its notification on One UI 9. That has to be dumped on the S26 Ultra before the parser is
  finalised (§2.3). The app has not been run on the device yet.

So what we can truthfully show is: **the next alarm** (time, how long until it rings, and
which app set it), plus **every running or paused timer** that a clock app is showing in
its notification. We would show nothing that is merely stored in an app. That matches the
request: "only ENABLED alarms and RUNNING timers". The catch is that for alarms, "enabled"
narrows to "the next one".

Mockups (`mockup.html`, 915x412 CSS px at 2x, rendered with Playwright, Clock + weather as the
right-hand widget):

| | Recommended | Alternative |
|---|---|---|
| Analog | `analog-day.png`, `analog-night.png` (A1 "on the ring") | `analog-alt.png`, `analog-alt-night.png` (A2 "sub-dials") |
| Digital | `digital-day.png`, `digital-night.png` (D1 "status lines") | `digital-alt.png`, `digital-alt-night.png` (D2 "status pill + last-minute takeover") |

Query params: `?face=analog|digital`, `&night`, `&alt`.

Evidence labels: **[V]** verified in official docs or source code, **[R]** reported (forums,
press, search extracts), **[A]** assumed, **[D]** needs a device test on the S26 Ultra.

> Research note: this session could reach `developer.android.com` and GitHub only.
> `android.googlesource.com`, sammobile.com, community.home-assistant.io and docs.kustom.rocks
> were blocked, so Samsung-specific claims come from search-result extracts. The AOSP DeskClock
> source was read from the LineageOS mirror (`LineageOS/android_packages_apps_DeskClock`,
> HEAD `dc237b5`, 2026-11-15).

---

## 1. Alarms

### 1.1 `getNextAlarmClock()`: the reliable part [V]
- `AlarmManager.getNextAlarmClock()` "Gets information about the next alarm clock currently
  scheduled. The alarm clocks considered are those scheduled by any application using the
  `setAlarmClock` method." It returns `null` if no alarm is set. [V, docs]
- `AlarmClockInfo.getTriggerTime()` is the wall-clock time in ms. `getShowIntent()` is a
  `PendingIntent`, and its `creatorPackage` names the app that set the alarm (API 24+). [V, API]
- `ACTION_NEXT_ALARM_CLOCK_CHANGED` is "sent after the value returned by getNextAlarmClock()
  has changed", and it is "only sent to registered receivers". The receiver therefore has to be
  registered at runtime, in `NightstandActivity` or `WatcherService`, not in the manifest. [V]
- AOSP DeskClock schedules its alarms with `am.setAlarmClock(info, op)` (`Utils.java:280`). [V,
  source] Google Clock is closed source but is built from DeskClock, and the status-bar alarm
  icon relies on this API. [A, high confidence]
- Samsung Clock: its alarms show in the status bar and on AOD, and Home Assistant / Kustom
  users read Samsung alarms through this API. [R] So it uses `setAlarmClock`. [R → D]

### 1.2 Known traps with `getNextAlarmClock()` [R]
- **Other apps use the same API.** Reports name Samsung Calendar reminders, Samsung Routines,
  and Tasker's "reliable alarms". If one of them is earlier, it *is* the "next alarm clock",
  and it hides the real wake-up alarm. Filtering by `creatorPackage` only drops the impostor;
  it cannot recover the alarm behind it. [R: Home Assistant companion-app threads, Tasker group]
- **Samsung may report the alarm early**, e.g. 08:55 for a 09:00 alarm. That has been linked
  to Clock's "upcoming alarm" notification lead time (default 5 min). [R] **[D]: compare
  `triggerTime` with the alarm actually set.**
- **Mitigations to build in:**
  1. Label the source. Show the alarm only when `creatorPackage` is a known clock app
     (`com.sec.android.app.clockpackage`, `com.google.android.deskclock`,
     `com.android.deskclock`), or one the user allowed in settings ("Show next alarm from:
     clock apps only / any app").
  2. Cross-check with the Samsung Clock "upcoming alarm" notification when it is posted
     (it carries the exact time in its text). [R → D]
  3. Re-read the value on every minute tick as well as on the broadcast.

### 1.3 Listing *all* enabled alarms: not possible [V/R]
- No public API reads another app's alarms. `AlarmClock` intents can only *create* alarms or
  timers, or show the list in the clock app's UI. [V, docs]
- AOSP DeskClock's `ClockProvider` is declared with `android:exported="false"` (manifest line 166). [V, source]
- Samsung Clock: a `com.sec.android.app.clockpackage.permission.READ_ALARM` permission is
  listed for Samsung's clock packages. [R, sysapps.bayton.org] Its protection level is not
  public. Samsung's own permissions of this kind are signature/privileged in practice, so a
  sideloaded app cannot hold it. [A] The legacy `content://com.samsung.sec.android.clockpackage/alarm`
  URI is undocumented and not reachable without that permission. [A] **[D]: one
  `adb shell dumpsys package com.sec.android.app.clockpackage | grep -A3 READ_ALARM` settles it.**
  Even if it were readable, depending on a private schema breaks with Clock updates. Not recommended.
- `Settings.System.NEXT_ALARM_FORMATTED` is deprecated since API 21 and shows at most one
  string. It may serve as a cross-check only. [V deprecated; D whether Samsung still writes it]

### 1.4 What to show for alarms
The next alarm only, if it is within **24 h** on digital faces and within **12 h** for the
analog ring marker (so the hour position is unambiguous). Show the time, "in 6 h 49 m", and
the source app if it is not a clock app. Snoozed: One UI 8.5+ posts a snooze countdown
notification [R], and `getNextAlarmClock()` should then return the snooze time [A/D]. Show it
as "Snoozed · 6:39".

---

## 2. Timers

### 2.1 How AOSP / Google-style DeskClock posts timers [V, source `TimerNotificationBuilder.java`]
- One ongoing notification for all unexpired timers (`TIMER_MODEL_NOTIFICATION_CHANNEL_ID`):
  - `setOngoing(true)`, `setCategory(CATEGORY_ALARM)`, `setShowWhen(false)`,
    `VISIBILITY_PUBLIC`, `setGroup(...)`, `setRequestPromotedOngoing(true)` (Live Update).
  - **Style: `NotificationCompat.MetricStyle`** with one `Metric(TimeDifference, stateText)`:
    - running → `TimeDifference.forTimer(Instant endTime, FORMAT_CHRONOMETER)`. The end time is
      `now + remaining (+1 s rounding)`.
    - paused → `TimeDifference.forPausedTimer(Duration remaining, ...)`.
  - `stateText` is "Timer", the timer's label, "Timer paused", "N timers" or "N timers paused".
    **With several timers, only the first one (soonest) is in the notification,** together
    with the count.
- Time's up: a separate notification on `FIRING_NOTIFICATION_CHANNEL_ID`, `PRIORITY_HIGH`, with
  a `forTimer` metric whose end time is in the past (overtime) and the text "Time's up" or the
  label. "Missed timer" notifications also exist and must be ignored.
- The stopwatch has its own builder, with the same MetricStyle and `forStopwatch`. Ignore it.

### 2.2 Platform APIs to read it [V, docs]
- Android 17 (API 37) adds `Notification.MetricStyle` and `Notification.Metric.TimeDifference`.
  The getters are `isTimer()`, `isStopwatch()`, `getZeroTime()` (an `Instant`, null if
  paused or elapsedRealtime-based), `getZeroElapsedRealtime()`, `getPausedDuration()`
  (a `Duration`, non-null only when paused), and `getFormat()`. `MetricStyle.getMetrics()` and
  `getCriticalMetric()` are the style getters.
- How a listener recovers the style: `Notification.Builder.recoverBuilder(ctx, n).style as?
  Notification.MetricStyle`. [A: recoverBuilder rebuilds platform styles from extras; MetricStyle
  is a platform style, so it should work; **D**] This **needs `compileSdk = 37`**; the app is on 36.
- Pre-Android-17 / legacy fallback: `n.extras` has `android.showChronometer` (=`EXTRA_SHOW_CHRONOMETER`)
  and `android.chronometerCountDown` (`EXTRA_CHRONOMETER_COUNT_DOWN`), and `n.when` is then the
  zero time. A count-down chronometer is a running timer with `endsAt = when`. [V, docs] The
  Live Update guidelines tell apps to do exactly this for status chips. [V]
- Live Updates must **not** use custom `RemoteViews` and must be standard/BigText/Call/Progress/
  **Metric** style. [V] Samsung's Now Bar has shown timers since One UI 7 and was moved onto
  Live Updates in One UI 8. [R] So on One UI 9 the Samsung timer notification is *probably*
  a parseable standard notification. [A → **D**]

### 2.3 Samsung Clock specifics: unknown until dumped [R/D]
- Multiple simultaneous timers since One UI 5.1. The notification lets you switch between
  timers, which suggests one notification for all timers, not one per timer. [R, SamMobile extract]
- A separate "mini timer" popup can be turned on. It is not a notification. [R, Samsung support]
- **Unknown [D]:** the channel id; MetricStyle vs chronometer vs Samsung-private extras; whether
  all timers or only the first are exposed; the paused and time's-up representation; whether
  labels are included. **Step 0 of the plan is a Debug-tab "Dump clock notifications" button**
  that logs every extras key/value, `when`, `category`, `channelId`, `flags` and style class for
  `com.sec.android.app.clockpackage` and `com.google.android.deskclock`. It needs 3 captures:
  one running, one paused, and two running at once, plus time's up.
- Text fallback: if neither MetricStyle nor chronometer is present, parse `EXTRA_TEXT`/`TITLE`
  for `^\d{1,2}:\d{2}(:\d{2})?$`. If the text is unchanged across two posts ≥ 1.5 s apart, or a
  "Resume" action exists, the timer is paused; otherwise derive `endsAt` from `postTime + value`.
  This fallback is weak; prefer the structured fields.

### 2.4 Parsing rules (all sources)
| Signal | Meaning |
|---|---|
| category `stopwatch`, `isStopwatch()`, or count-*up* chronometer | ignore |
| `isTimer()` + `getZeroTime()` in the future | RUNNING, `endsAt = zeroTime` |
| `isTimer()` + `getZeroElapsedRealtime()` | RUNNING, `endsAt = now + (zeroER - elapsedRealtime())` |
| `getPausedDuration() != null` | PAUSED, `remaining = pausedDuration` |
| zero time in the past, or high-priority firing channel / "Time's up" | RINGING (show "Time's up" until removed) |
| "Missed timer" text / non-ongoing | ignore |
| `showChronometer && chronometerCountDown` | RUNNING, `endsAt = n.when` |
| notification removed | timer gone (reset or dismissed) |

Only packages on an allowlist are parsed: the two above plus `com.android.deskclock` and a
user-addable list. `NotificationFeed` currently *drops* ongoing notifications (`!it.isOngoing`),
so timers need their own pass over `activeNotifications`. They must not leak into the
notification strip.

---

## 3. Data model and update cadence

```kotlin
data class NextAlarm(
    val triggerAt: Long,          // wall clock ms, from AlarmClockInfo.triggerTime
    val sourcePkg: String?,       // showIntent.creatorPackage
    val fromClockApp: Boolean,
    val snoozed: Boolean = false,
)

data class RunningTimer(
    val key: String,              // sbn.key (+ metric index)
    val pkg: String,
    val label: String?,           // null for the default "Timer"
    val state: State,             // RUNNING, PAUSED, RINGING
    val endsAt: Long?,            // wall clock ms; RUNNING / RINGING
    val pausedRemainingMs: Long?, // PAUSED
    val countHint: Int = 1,       // "3 timers" when the app exposes only the first
) { enum class State { RUNNING, PAUSED, RINGING }
    fun remainingMs(now: Long) = pausedRemainingMs ?: ((endsAt ?: now) - now)
}

// in DisplayData
var nextAlarm: NextAlarm? = null
var timers: List<RunningTimer> = emptyList()   // sorted: RINGING, RUNNING by endsAt, PAUSED
```

**Cadence.** Nothing is polled.
- Alarm: read on `onShown`, on `ACTION_NEXT_ALARM_CLOCK_CHANGED`, and on the existing
  per-minute tick (the "in 7 h 12 m" text).
- Timers: event-driven by `onNotificationPosted/Removed`. Countdowns are computed from
  `endsAt` at draw time.
- **Per-second redraw only while a RUNNING or RINGING timer exists and the page is
  visible.** `Page.needsSeconds` becomes `secondHandOn || data.timers.any { it.state != PAUSED }`.
  Otherwise the per-minute tick stays as it is.
- Timers whose `endsAt` passed more than 5 min ago without a removal are dropped, in case the
  app crashed or the notification is stale.

---

## 4. Recommended UI

### Analog: **A1 "on the ring"** (recommended), `analog-day.png` / `analog-night.png`
- **Timer as a bezel arc.** A thick accent arc over the tick ring runs *from the minute
  hand to the minute the timer ends*, with a ringed end-cap at the end minute. The ticks under
  it are hidden. It shrinks as the minute hand advances, so the face shows **when the timer
  ends**, like a dive-watch bezel. It uses only the end time, which every source gives, and
  never the original duration, which clock notifications do not expose. That is why A1 beats
  a "% remaining" ring. Shown only for timers under 60 min; longer timers show the readout only.
- **Alarm as a bell on the ring** at the alarm's *hour-hand* position, only when it is under
  12 h away. It reads like the alarm pointer on a travel alarm clock: "when the hour hand gets
  here". The ticks under the bell are hidden.
- **Readout inside the dial:** hourglass + `4:59` (0.17 r, accent, heavy) and, smaller,
  bell + `6:30 AM` (0.095 r, secondary). It sits in the half of the dial **opposite the hour
  hand** (top in the day mock at 7:41, bottom in the night mock at 11:41), so the thick hand
  never covers the digits. The minute and second hands may sweep over it, as on any watch.
- Multiple timers: the arc shows the soonest RUNNING timer only. The readout shows `4:59 +1`.
  Paused only: no arc, readout `❚❚ 12:00` in secondary.
- RINGING: readout "Time's up" in accent. The clock app's own full-screen alarm will usually
  cover Nightstand anyway. [D]
- Legibility at 1–2 m: the arc is 0.075 r (≈14 dp) thick and the timer digits ≈33 dp. Both
  are legible. The alarm line (≈18 dp) matches the existing date line on Big clock (15 dp).
  At night, contrast comes from *thickness*, not hue: everything is one red, and the
  remaining ticks stay at the existing secondary alpha.
- Same treatment on Big clock (bigger r) and Clock + up next.

**A2 "sub-dials" (alternative), `analog-alt.png`.** A chronograph sub-dial at 6 o'clock shows
the countdown, a ring and the label; the alarm text sits under the 12. The ring is untouched,
so it is closer to iOS purity. Rejected as default because the sub-dial ring needs the timer's
*total duration*, which notifications don't carry (we would have to guess it from the first
time we saw the timer). The small caps text is also too small for 2 m, and the hands cross
both sub-dials half the time: in `analog-alt-night.png` (11:41) the hour hand covers the alarm text.

### Digital: **D1 "status lines"** (recommended), `digital-day.png` / `digital-night.png`
- Under the date: **bell `6:30 AM` · in 10 h 49 m**. The time is primary and the
  relative part secondary, at ≈25 dp.
- Below that, **timer chips**, soonest first: an outlined pill with hourglass, `4:59` (heavy,
  accent) and the label (secondary). Paused chips are grey with a pause glyph, `❚❚ 12:00 Rice`.
  At most 2 chips; a third becomes `+1`.
- The time + date block shifts up to stay vertically centred. When there is no alarm and no
  timer, the layout is exactly today's.
- Big clock digital: the bottom date line becomes `WED, NOV 25 · 🔔 6:30 AM · ⏳ 4:59`.
- Clock + up next: the same two lines in `drawDigital`, above the notification strip.

**D2 "status pill + takeover" (alternative), `digital-alt.png`.** The clock column stays pure,
with the alarm in a small pill at the top right. In a timer's **last 60 s** the right-hand
widget is replaced by a big countdown with a ring. Rejected as default because nothing on the
face shows a long-running timer except the pill, and the pill is small at 2 m.

### Where else: the last-minute takeover
Recommend it as an **option for both faces**: "Big countdown in a timer's last minute",
default **on by day, off at night**. At night the change in luminance would wake you, and the
clock app's own alarm will do that at zero anyway. It replaces only the right-hand widget,
never the clock. A minute-scale ring is honest because the total is 60 s by definition. When
the timer ends or is removed, the widget returns.

---

## 5. Open questions for the user
1. Alarms: is **"next alarm only"** acceptable? Listing all enabled Samsung alarms is not
   possible without root.
2. If the next "alarm clock" comes from a non-clock app (Calendar reminder, Routines, Tasker),
   should we hide it (default) or show it with the app name?
3. Show the alarm always, or only on Big clock and at night? Show it within 12 h or 24 h?
4. Last-minute takeover: on by day / off at night as proposed?
5. Should the stopwatch be shown too? It was excluded per the request.
6. Analog: is the extra readout text inside the dial wanted, or should the face stay iOS-pure
   (arc + bell only)?

---

## 6. Implementation plan

**Step 0 (device, before any parser code).** Add a Debug tab button "Dump clock notifications"
that logs all extras, the style class and `when` for allowlisted packages. Then capture these
on the S26 Ultra: one running, one paused, two running, time's up, a snoozed alarm, and the
upcoming-alarm notification. Also run `adb shell dumpsys alarm | grep -A5 "next alarm clock"`
and the `READ_ALARM` `dumpsys package` check. Record the results in this file.

**New classes** (`com.nightstand.clock`):
| Class | Role |
|---|---|
| `AlarmSource` | `getNextAlarmClock()` → `NextAlarm?`; registers `ACTION_NEXT_ALARM_CLOCK_CHANGED` at runtime; clock-app allowlist via `creatorPackage` |
| `TimerParser` (pure) | Input: a small `NotifFacts` value (pkg, category, channel, flags, when, showChrono, countDown, title, text, actions, metric list mapped to plain data); output `RunningTimer?`. All rules in §2.4. No Android types, so it is unit-testable |
| `MetricReader` | API 37 only: `recoverBuilder(...).style as? MetricStyle` → `NotifFacts.metrics`; `Build.VERSION.SDK_INT >= 37` guard; bump `compileSdk` to 37 |
| `TimerFeed` | In `NotificationFeed.rebuild()`: a second pass over ongoing notifications from allowlisted pkgs → `TimerParser` → `snapshot`; `StandByState.notifyChanged()` |
| `ClockStatus` (pure) | Formatting and geometry: `"in 7 h 12 m"`, `"4:59"`/`"1:04:59"`, chip ordering, `alarmAngle()`, `timerArc(now)` (start/end degrees or null), `readoutTopHalf(hourDeg)` |

**Hooks into existing code:**
- `DisplayData`: add `nextAlarm`, `timers`. `NightstandActivity` fills them, as it does events and notifications.
- `Page.needsSeconds` (`ClockSplitPage`, `BigClockPage`, `ClockAgendaPage`): `|| data.timers.any { it.state != PAUSED }`.
- `AnalogClock.draw(c, cx, cy, r, t, p, showSeconds, status: ClockStatus.Frame? = null)`:
  hide ticks under the arc and bell, draw the arc and end-cap after the ticks, draw the bell, then draw
  the readout after the numerals and **before** the hands. All existing callers keep working
  through the default `null`.
- `ClockSplitPage` digital branch: measure the extra lines, shift `base` up, then draw the alarm
  line and chips (`StatusLine.draw(c, x, y, frame, p)`, a shared helper).
- `BigClockPage`: analog passes the frame. Digital appends to the bottom date line.
- `ClockAgendaPage.drawDigital`: same helper; include the extra height in `blockH`.
- Takeover: `ClockSplitPage.onDraw` checks `frame.lastMinuteTimer` and, if the option is on,
  calls `drawTakeover(c, rightRect, timer)` instead of `drawRight`.
- Settings → Look: "Alarm & timers on clock" (on), "Big countdown in last minute"
  (day / always / never). Settings → Views: allowlisted alarm sources.

**Tests:**
- `TimerParserTest`: MetricStyle running/paused/elapsedRealtime-based; chronometer count-down;
  stopwatch ignored; time's up; missed ignored; non-allowlisted pkg ignored; text fallback;
  stale (>5 min past) dropped. Each Samsung dump becomes a fixture once captured.
- `ClockStatusTest`: "in" formatting (59 s, 1 h 0 m, 23 h 59 m); 12 h/24 h windows; arc
  angles across the 59→0 wrap; >60 min timer has no arc; readout half for each hour.
- `AlarmSourceTest`: creatorPackage filter, null info, non-clock app hidden or labelled.
- `PagePreviewTest`: add `alarms_timers-{analog,digital}-{day,night}` and the takeover state,
  with fake `DisplayData`, so the Canvas output can be compared with these mockups.
- On-device: test plan rows for running, paused, 2 timers, time's up over Nightstand,
  snooze, Calendar reminder earlier than alarm, and 1 Hz redraw battery cost overnight.

---

## Sources
- AlarmManager (`getNextAlarmClock`, `setAlarmClock`, `ACTION_NEXT_ALARM_CLOCK_CHANGED`): https://developer.android.com/reference/android/app/AlarmManager
- AlarmClock intents: https://developer.android.com/reference/android/provider/AlarmClock
- Notification extras (`EXTRA_CHRONOMETER_COUNT_DOWN`, `EXTRA_SHOW_CHRONOMETER`, `CATEGORY_ALARM`, `CATEGORY_STOPWATCH`): https://developer.android.com/reference/android/app/Notification
- MetricStyle (API 37): https://developer.android.com/reference/android/app/Notification.MetricStyle and guide https://developer.android.com/develop/ui/views/notifications/metric-style
- TimeDifference getters: https://developer.android.com/reference/androidx/core/app/NotificationCompat.Metric.TimeDifference
- Live Update requirements and status chip `when`/chronometer: https://developer.android.com/develop/ui/views/notifications/live-update
- AOSP DeskClock (LineageOS mirror): https://github.com/LineageOS/android_packages_apps_DeskClock (`TimerNotificationBuilder.java`, `Utils.java:280`, `AndroidManifest.xml:166`)
- Samsung clock package permissions (READ_ALARM): https://sysapps.bayton.org/packages/com.samsung.android.app.clockpack , https://sysapps.bayton.org/packages/com.sec.android.app.clockpackage
- Old provider access removed: https://kiwix.lema.org/stackoverflow.com_en_all_2023-11/questions/6185401/how-can-i-access-alarm-content-provider
- Wrong "next alarm" on Samsung (Calendar/Routines/Tasker, 5-min offset): https://community.home-assistant.io/t/next-alarm-sensor-not-updating-stuck-in-the-past-samsung-galaxy-s22/613255 , https://community.home-assistant.io/t/psa-taskers-time-triggers-may-be-interfering-with-the-next-alarm-sensor-of-the-companion-app/463715 , https://docs.kustom.rocks/docs/common_issues/wrong_alarm/
- Samsung multiple timers (One UI 5.1): https://sammobile.com/news/one-ui-5-1-feature-focus-multiple-timers-clock-app
- Now Bar timers/alarms, snooze countdown (One UI 8.5): https://www.sammobile.com/news/one-ui-8-5-now-bar-clock-app-alarms/ , https://www.sammobile.com/news/one-ui-8-5-does-not-stop-you-snoozing-but-it-will-count-every-second/
- Samsung mini timer: https://www.samsung.com/ae/support/mobile-devices/why-isnt-the-mini-timer-showing-after-one-ui-7-update/
- Samsung upcoming-alarm notification: https://eu.community.samsung.com/t5/galaxy-s23-series/next-alarm-on-always-on-display-and-lock-screen/m-p/9541667
