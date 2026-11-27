# Changelog

## v1.0.6
- **Charging ring** (top-left): level arc, charge-limit tick (Samsung limit learned automatically), centre fills to a solid disc at the limit, lightning bolt while plugged in; green by day, night colour at night. Option: hide above 95 % (Look → Charging). *(1.0.5–1.0.6)*
- **Night photos:** when night begins on Photos, the display moves to Clock + weather; swiping back shows photos in full colour, dimmed with the clock. *(1.0.4)*
- **Alarm marker:** a triangle on the clock's tick ring — solid within 12 h, hollow 12–24 h. *(1.0.2–1.0.3)*
- **Alarms fix:** Google Clock alarms that report no owner are shown; Routine/Bedtime times can be hidden ("Not my alarm"). Pull down to refresh in settings. *(1.0.1)*

## v1.0 — first stable version
Bedside StandBy-style display for the Galaxy S26 Ultra.
- **Trigger:** charger + sideways + still + locked → shows over the lock screen; closes when lifted or unplugged.
- **Night mode:** room-light driven; pure red (or deep / warm / amber); brightness by swipe or slider; second hand off at night (power).
- **Views:** Clock + up next (Google Calendar, notifications) · Clock + weather (iPhone layout) · Big clock · Clock + photos · Clock + now playing (Spotify / any player, with controls). Swipe to cycle; order, on/off and start view in settings.
- **Alarms & timers:** next clock alarm within 24 h; running/paused timers incl. Android 17 Google Clock; last-minute big countdown; Samsung Routines/Bedtime filtered; recheck after them; alarm learned from notifications and earlier nights ("expected").
- **Settings app:** Home · Views · Look · Setup · Debug (hidden by default); crash reports on Home.
- **Robustness:** 71 tests (trigger path, lifecycle, gestures, corrupted settings, fuzzing); 5 screen shapes verified.
- **Power:** wake-up accelerometer with batching (no all-night wake lock), low frame-rate hint, per-minute redraw at night.
- **Size:** 1.9 MB release build.

## 0.x — development
0.1 prototype → 0.2 views & analog clock → 0.3 weather/photos/now playing → 0.4 pure red, weather centring → 0.5 tabbed settings → 0.6 size 23→1.9 MB → 0.7.x alarms & timers, Android 17 timers, learned alarms, robustness & power, iPhone weather layout.
