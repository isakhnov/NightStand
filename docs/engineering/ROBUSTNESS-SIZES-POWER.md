# Robustness, screen sizes and steady-state power (v0.7.6)

## Tests (93, all JVM — `./gradlew testDebugUnitTest`)

| Area | What's covered | File |
|---|---|---|
| Trigger | posture classes, stable hold, motion drop, **10 min of table vibration → no flicker**; trigger policy rules + **random-walk invariant** (never shows unless charging ∧ landscape ∧ locked ∧ not visible) | PostureDetectorTest, TriggerPolicyTest, RobustnessPureTest |
| Whole trigger path | **Robolectric: watcher service + power-connected broadcast + simulated accelerometer + locked keyguard → launch alert posted** | RobustnessAndroidTest |
| Display lifecycle | **every view as start page × night/day: opens, renders, closes on "charger pulled"** | RobustnessAndroidTest |
| Gestures | swipe next/previous **with wrap-around**, vertical brightness drag, single tap vs double tap | RobustnessAndroidTest |
| Settings | **corrupted/unknown values** (renamed enums, garbage page order) fall back to defaults; brightness clamped | RobustnessAndroidTest |
| Night mode | thresholds, hysteresis, schedule fallback | NightModeControllerTest |
| Alarms | clock-apps-only + 24 h rule, internal holders (Routines/Bedtime), notification parser, memory expiry, **learned patterns** (weekday, contradiction, Samsung early report, 8-day expiry) | AlarmsTimersTest, AlarmPatternsTest, RobustnessAndroidTest |
| Timers | chronometer, custom layout, **Android 17 MetricStyle**, paused/ringing, labels, **2 000 random notifications never throw** | AlarmsTimersTest, RobustnessPureTest |
| Weather | real Open-Meteo shape + rounding; malformed reply throws (caught by refresh) | RobustnessAndroidTest |
| Photos | Ken Burns fit→fill→pan; **extreme shapes (panorama, 1×1, 3×50000) stay finite** | ViewsLogicTest, RobustnessPureTest |
| Smart photos | cosine/fp16, look-alike radius edges, burst grouping, logistic head (learns, gated at 3 swipes, anchors stop collapse), selection ranking, look-ahead queue, prompt table vs Python golden vectors, preprocessing; **Robolectric: DB insert/hide/restore/reset, swipe → look-alikes → undo/restore, junk skipped, one per burst, model loaded only for unscored photos and closed when idle/hidden/trimmed, background trickle + nightly cap, Smart off / model missing / runtime failure → random, SHA-256 gate; gestures: ↓ on the photo hides by day, nothing at night, brightness only on the clock half** (fake embedder, never the real model) | SmartPureTest, SmartAndroidTest |
| Crash capture | handler stores the report | RobustnessAndroidTest |
| Rendering | every view × clock style × day/night on **5 screen shapes with stress data**; every settings tab | ScreenSizesTest, PagePreviewTest, SettingsPreviewTest |

Bugs these found and fixed: weather overflowed on near-square screens/tablets;
tap detection assumed the wall clock is far from 0 (first tap after boot could
read as a double-tap) — now uses the touch event's own clock; tap callback no
longer depends on the view being attached.

Not covered by automated tests (device-only): Samsung-specific behaviour
(battery management, full-screen intent on One UI, sensor batching), real
notification shapes from other clock apps, OLED burn-in.

## Screen sizes

Contact sheets in this folder (top row analog/day, bottom digital/night; each
column a view): `1-small-640x360`, `2-s26ultra-915x412`, `3-tall-800x360`,
`4-fold-882x840`, `5-tablet-1280x800`. All views draw without errors from a
small phone to a tablet; sizes scale from the screen height with width limits.
Note: Robolectric doesn't apply "…" ellipsis, so very long titles look cut in
these renders; on the device they ellipsize.

## Steady-state power

Nightstand spends hours in one state: plugged in, display on, nothing changing.

| Item | Before | Now |
|---|---|---|
| Watcher while plugged | partial **wake lock all night** + 5 Hz accelerometer | **wake-up accelerometer, 1 s batching, no wake lock** (CPU sleeps between batches); fallback: wake lock only while the screen is off |
| Display refresh | system default (120 Hz LTPO) | `setRequestedFrameRate(CATEGORY_LOW)` on Android 15+: the panel can idle at its lowest rate |
| Redraws at night | every second (second hand) | **once a minute** — second hand off at night by default (Look → "Second hand at night") |
| Light sensor | default rate | 1 Hz, 2 s batched |
| Calendar | re-queried every minute | content observer + safety re-query every 5 min |
| Photo pan (day) | 30 fps | 15 fps; still at night |
| Brightness at night | — | user-set (20 % default), red-only pixels on OLED |

Remaining unavoidable cost: the lit display. Everything else is now
event-driven or batched.
