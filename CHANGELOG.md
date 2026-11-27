# Changelog

## v1.2 — leaner, with backups and updates
**Added**
- **About & credits** (Setup): Open-Meteo attribution (CC BY 4.0), Apple's MobileCLIP licence, LiteRT/AndroidX/Kotlin.
- **Back up learning** (Views → Photos ⚙): export/import swipes, hidden/restored photos and learned taste to a file; matches photos by capture time on a new phone.
- **Update available** card on Home: checks GitHub releases (vX.Y with an APK) once a day.
- **Automatic builds** (GitHub Actions): every push builds and tests; publishing a vX.Y release attaches the signed APK.
- **Motion to wake** (Look → Night mode, on by default): a bump of the nightstand brightens the night display for a few seconds.

**Removed**
- Big clock view (Weather, Up next, Now playing and Photos remain).
- Learned "expected" alarms and the alarm memory from Google's notifications; alarms come from Android plus the recheck after hidden Routine times.
- Timer readers other than Android 17's (Google Clock); the "Alarms from other apps" placeholder.
- Prototype drawing files and research for dropped ideas (Outlook/Teams, old settings mockups).

## v1.1 — smart photos
- **Smart selection** (Views → Photos ⚙): an on-device image model (MobileCLIP-S0, run by LiteRT from Google Play services) scores each photo once, just before it would show; scores persist, so known photos never load the model. The model is downloaded on first use (or imported), not bundled — APK 2.9 MB.
- **Swipe down on a photo** (by day) hides it and its look-alikes ("junk, never again"), with Undo; strictness Strict / Normal / Wide. No swipe-to-hide at night; brightness drags on the clock half of the Photos view.
- **One per burst:** photos seconds apart that look alike show as one.
- **Categories:** firearms are keep; food, memes, drawings and other utility shots are junk.
- **Memory:** model loads only for unscored photos and unloads after 30 s idle, on hide, screen off or memory pressure. Background scoring while the Photos view is up (≤300 per night).
- **Hidden photos** review with Restore; Reset learning; Debug shows model state, counts and per-photo time (~56 ms on the S26 Ultra).

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
