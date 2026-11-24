# View 04: Now Playing (Spotify first, any player)

**Verdict: feasible, low risk, no new permissions.** Build it on the
platform `MediaSession` API, using the Notification Listener access Nightstand
already holds. Do **not** use the Spotify App Remote SDK or the Web API. Starting
playback when Spotify isn't running is the one weak spot, and it can be handled
with one fallback.

Mockups: `mockup-day.png`, `mockup-night.png` (source `mockup.html`, `?night` for
night mode; 915x412 CSS px at 2x, rendered with Playwright). The album cover is a
generated SVG placeholder.

Evidence labels: **[V]** verified (official docs, or measured on a real phone
in the sibling `ytm-andr` project), **[R]** reported (forums, press),
**[A]** assumed, still to be checked on the S26 Ultra.

> Research note: `developer.spotify.com` was blocked from this session's
> network, so Spotify's own pages were read through search-result extracts
> only. URLs are listed in [Sources](#sources).

---

## 1. MediaSession route (recommended)

**Getting access.**
`MediaSessionManager.getActiveSessions(ComponentName(ctx, NotificationFeed::class.java))`
returns a `MediaController` for every active session. It throws
`SecurityException` if Notification access is off. [V] The `ytm-andr` project
does exactly this on a Samsung phone (`YTMLauncher/CLAUDE.md`, "Notification
Access" section). `addOnActiveSessionsChangedListener(listener, component, handler)`
pushes changes, so no polling is needed. [V, Android API]

**What Spotify publishes.** [A, standard for MediaSessionCompat players]
- `METADATA_KEY_TITLE`, `ARTIST`, `ALBUM`, `DURATION` (ms).
- Art as a bitmap in `ALBUM_ART` / `ART`, and possibly a URI in
  `ALBUM_ART_URI` / `ART_URI`. Android's docs say the bitmap may be downscaled
  and that the URI key is the high-res path. [V, docs] Nothing public says which
  keys Spotify fills, or at what size. [unknown]
- Plan: use the bitmap if it is at least about 300 px. Otherwise load the URI
  through `ContentResolver.loadThumbnail` (it is usually a `content://` URI that
  the session grants to the controller). If both fail, fall back to a
  placeholder art tile. Never fetch art from the network.
- Known art bugs: there are reports of Spotify sending art only for the first
  track (Android Auto), of wrong art in media controls on Android 12, and of
  other players' art arriving low-res at first and then updating. [R]
  **Re-read the art on every `onMetadataChanged` and never cache it per
  session.** Cache by `title|artist`.

**PlaybackState.** `state`, `position`, `lastPositionUpdateTime`,
`playbackSpeed`, `actions`. The position is a snapshot, so extrapolate it as
`pos + (elapsedRealtime() - lastPositionUpdateTime) * speed` while
`STATE_PLAYING`. [V] This is a listed trap in `ytm-andr/FINDINGS.md`. Players
don't send position ticks while playing; they update only on
state/seek/track change. [V, Android contract] The progress bar therefore needs
its own redraw timer (one frame per second, only while visible and playing),
not just the per-minute tick.

**Controls.** `transportControls.skipToNext()`, `skipToPrevious()`, `play()`,
`pause()`; `seekTo()` if the bar is draggable (optional). [V, API]
- Gate each button on `PlaybackState.actions`, but don't trust that alone.
  `ytm-andr` found that an advertised action isn't always usable. [V]
- Spotify's "previous" restarts the track when more than ~3 s in; that is
  correct iPhone behaviour too. [A]
- **Spotify Free:** skips are allowance-limited after the daily on-demand
  quota, which used to be 6 skips per hour on mobile (changed September 2025,
  current figure unpublished). [R] A refused skip shows up as no track change.
  It may also arrive as `STATE_ERROR`, or `ACTION_SKIP_TO_NEXT` may drop out of
  `actions`. Grey the button out from `actions`. [A]

**Calling from an Activity shown over the lock screen.**
`MediaController` calls are binder IPC to the player's session. They don't go
through the keyguard and need no foreground or unlocked state. [V,
architecture; ytm-andr commands YTM from a background service] Media control
also doesn't count as "sensitive" in the way that notification content does,
which matches the system lock-screen media player allowing the same actions
while locked. **No restriction expected.** [A, confirm on device] One
side-effect: when Nightstand is on screen, One UI's own lock-screen media card
isn't visible anyway.

**Session lifetime quirks.**
- Spotify's session stays registered while paused, but Spotify can stop its
  service and drop the session after a while paused. App Remote users report a
  disconnect about 30 s after pausing, and the exact media-session timeout isn't
  documented. [R/A] Treat the disappearance of the session as normal: the page
  shows the last-known track with a "Play" button (see §3).
- `getActiveSessions` is ordered by priority (most recently active first). [V,
  docs]

**Player-agnostic selection rule** [design]: pick the first session in `PLAYING` state, with
Spotify (`com.spotify.music`) preferred among ties; otherwise the most recent paused session,
Spotify preferred; ignore sessions with no title. YouTube Music works identically (proven by
ytm-andr probes A/C).

## 2. Alternatives considered

| | MediaSession | Spotify App Remote SDK | Spotify Web API |
|---|---|---|---|
| Setup | none (access already granted) | register a Spotify dev app with package name and signing-cert SHA-1, plus OAuth consent via the Spotify app | dev app, OAuth with PKCE, token refresh |
| Account | any | Free and Premium, per Spotify's 2018 statement [R] | **Premium required** for player endpoints. Since **Feb 2026**, new Development Mode apps also need the **owner** to have Premium, with 1 client ID and 5 users max; existing apps were moved over in March 2026, and the per-account quota changed July 2026 [V, press and Spotify blog] |
| Needs Spotify running | yes (session) | **no**: `connect()` starts Spotify, so this route can cold-start | no (Spotify Connect) |
| Art | bitmap/URI from session | `ImagesApi`, sized images | HTTPS image URLs (network) |
| Other players | YTM etc. for free | Spotify only | Spotify only |
| Policy risk | none | Spotify says dev mode "should not be relied on"; known reconnect issues after pause [R] | same, plus network and quota |

**Verdict:** for a personal, sideloaded, player-agnostic bedside page,
MediaSession wins on every row except cold start. The App Remote SDK is worth
keeping in mind only if the cold-start fallbacks in §3 prove too clumsy. It adds
an SDK dependency, a Spotify dashboard app (under the 2026 Premium-owner rule),
and an auth flow.

## 3. Starting playback when nothing is playing

The `ytm-andr` project tested every headless cold-start path against YouTube
Music, and **all failed** [V, FINDINGS.md E9]:
- `MediaBrowserCompat.connect()`: rejected by the player's `onGetRoot()`
  allowlist.
- Media-button broadcast to the player's `MediaButtonReceiver`: no session
  appeared.
- `KEYCODE_MEDIA_PLAY` through `AudioManager.dispatchMediaKeyEvent`: "Media
  button session is null".

For Spotify:
- A Stack Overflow question reports that Spotify's MediaBrowserService rejects
  third-party callers. [R] Expect the same allowlist (Android Auto, Wear,
  SystemUI, Assistant).
- SystemUI's own "media resumption" (the `EXTRA_RECENT` root hint) can do this
  only because SystemUI is platform-signed. [V, Android blog]

Options, best first:
1. **The media-key event still works while the session exists, even when
   paused.** `controller.transportControls.play()` resumes, which covers the
   common case "paused 10 minutes ago". [V]
2. **Tap Play while no session exists:** launch Spotify. The tap runs in a
   visible foreground Activity, so the launch isn't a background start. Then
   wait for the session through `OnActiveSessionsChangedListener` (up to about
   8 s) and send `play()` once it appears. Spotify keeps play state server-side,
   so it resumes the last context and position. Caveat: Spotify's UI comes to
   the front over the keyguard and asks for unlock, or opens behind it. [A] An
   alternative is to send `KEYCODE_MEDIA_PLAY` with
   `Intent.ACTION_MEDIA_BUTTON` explicitly to `com.spotify.music`'s receiver
   first. It's cheap to try and fails silently, which is the pattern `ytm-andr`
   probe D saw. [A]
3. Spotify App Remote `connect()` (wakes Spotify headlessly). Costs listed in
   §2.

**Recommendation:** the page only **auto-shows** while a session exists. When
no session exists, the page isn't offered in the pager, so cold start becomes a
non-issue for the bedside use case. If the user wants a "resume music" button
anyway, implement option 2 behind a test on the device.

## 4. Night mode and burn-in

- **Art at night:** show it as **red monochrome**, a luminance → black-to-red
  ramp at 55% opacity. This is the mockup's treatment, done in Android with a
  `ColorMatrixColorFilter` with rows `[0.2126R+0.7152G+0.0722B, ×0.23, ×0.12]`.
  It keeps the art recognisable, emits no blue or green light, and matches the
  rest of the red UI. Hiding art entirely would make the page look empty. A
  setting "Hide album art at night" is cheap to add.
- **Drop the coloured backdrop wash at night** (in the day it's a dominant
  colour from `androidx.palette`; Palette is optional, plain black is fine).
  Hollow play ring instead of a solid filled disc at night, so fewer lit pixels.
  Use a thinner progress bar.
- **Burn-in.** The controls and art are static for hours if the music plays all
  night.
  - Apply the clock page's pixel-shift: move the whole page layer by ±6 px on
    the per-minute tick.
  - Night-dim the page through the existing brightness override.
  - **Leave the page after a paused-idle timeout:** if paused or the session is
    gone for 5 min, the pager returns to the clock page. Sleep-timer listening
    then ends on the clock.
  - The progress bar and times change constantly, so they are self-refreshing.

## 5. Implementation plan (for later; no code changed now)

**Permissions:** none new. Reuses Notification access (`NotificationFeed`).
`<queries><package android:name="com.spotify.music"/></queries>` is needed
only for option 2 (the launch intent / `getLaunchIntentForPackage` under
package visibility).

**Classes (package `com.nightstand`):**
- `NowPlayingSource`: an object owned by `StandByState`'s lifecycle.
  - On activity start, `getActiveSessions` plus
    `addOnActiveSessionsChangedListener`. Picks the session using the §1 rule
    and registers a `MediaController.Callback` on it (`onMetadataChanged`,
    `onPlaybackStateChanged`, `onSessionDestroyed`).
  - Exposes `NowPlaying(pkg, appLabel, title, artist, album, durationMs, art:
    Bitmap?, state, posMs, posAt, speed, actions)`, then calls
    `StandByState.notifyChanged()`.
  - Catches `SecurityException`, which means "no access", and returns null.
  - Methods: `prev()`, `toggle()`, `next()`, `seek(ms)`.
  - Unregisters on stop. No service, no polling.
- `ArtLoader`: picks bitmap or URI (§1), downsamples to the page size, keeps an
  LRU of 4 keyed by `pkg|title|artist`. It also makes the night variant once per
  track: red ColorMatrix applied in an offscreen `Canvas`, so `onDraw` doesn't
  filter every frame.
- `NowPlayingView : View` (a pager page, same contract as the other pages).
  - `var night: Boolean`, `fun onMinuteTick()` for the clock and pixel-shift,
    `fun bind(np: NowPlaying?)`.
  - Draws art, title, artist, bar and times with `Canvas`, in the same style as
    `NightstandView`.
  - Hit rectangles for prev, play and next. A tap on the art or text keeps the
    existing "tap to brighten" behaviour. Double-tap still unlocks.
  - Self-schedules `postInvalidateDelayed(1000)` only while
    `night == false || state == PLAYING`; at night, every 5 s is enough.
  - Button press feedback is a 120 ms scale and alpha change.
- Pager integration: offer the page while `NowPlayingSource.current != null`.
  - **Auto-select it** when the session goes to `PLAYING` while StandBy is
    showing (only if the user hasn't swiped in the last ~30 s).
  - Return to the default page after 5 min paused or when the session is gone.
- Prefs: preferred player (default Spotify), "Auto-show when music plays"
  (default on), "Hide art at night" (default off).

**Tests:** unit-test `pickSession()` ordering, position extrapolation, the
action gating, and the auto-select or idle-return timers (pure functions, the
same style as `TriggerPolicy`).

**On-device checks (S26 Ultra):**
1. Which art keys Spotify fills, and at what resolution (dump with a debug log).
2. Prev/next/play from the lock-screen Activity, with Spotify Premium and Free.
3. How long Spotify keeps the session paused before it's destroyed.
4. The cold-start option 2 behaviour over the keyguard.
5. YouTube Music parity.

## Open questions for the user
1. Spotify **Premium or Free**? On Free, "next" may be refused by skip limits;
   the page will grey it out but can't override it.
2. Should the page **auto-take-over** when music starts? It is the
   recommendation, overridable by a recent swipe.
3. Do you need **"start music"** from a cold state, or is "control what's
   already playing or paused" enough? The second needs nothing special. The
   first means option 2 (it opens Spotify) or adopting the App Remote SDK.
4. Album art at night: red-monochrome (mock) or hidden?
5. Draggable seek bar, or display only? Seeking by accident in the dark is a
   real risk; the recommendation is display only.

## Sources
- Android `MediaMetadata` (ALBUM_ART vs ALBUM_ART_URI, bitmap downscaling): https://developer.android.com/reference/android/media/MediaMetadata ; AOSP source https://code.yawk.at/android/android-9.0.0_r35/android/media/MediaMetadata.java
- Android media controls (actions derived from session state): https://developer.android.com/media/implement/surfaces/mobile
- Media resumption / `EXTRA_RECENT`, SystemUI flow: https://android-developers.googleblog.com/2020/08/playing-nicely-with-media-controls.html
- `onGetRoot` as access control / package allowlists: https://developer.android.com/media/legacy/audio/mediabrowserservice
- Report: Spotify MediaBrowserService rejects third-party apps: https://coderapp.vercel.app/answer/60311906 (mirror of a Stack Overflow question; unconfirmed)
- Spotify art quirks: https://community.spotify.com/t5/Android/Spotify-only-provides-Album-art-for-first-track-in-Android-Auto/m-p/4389192 ; https://community.spotify.com/t5/Android/Media-controls-show-the-wrong-album-art/m-p/5641445
- Spotify media broadcast intents (read-only alternative): https://developer.spotify.com/documentation/android/guides/android-media-notifications/
- Spotify App Remote SDK: https://developer.spotify.com/documentation/android ; https://github.com/spotify/android-sdk ; free + Premium control: https://developer.spotify.com/blog/2018-09-14-updates-mobile-platform ; disconnect after pause: https://community.spotify.com/t5/Spotify-for-Developers/iOS-amp-Android-Remote-SDK-loses-connection-after-paused-for-30s/td-p/7077461
- 2026 Development Mode changes: https://developer.spotify.com/blog/2026-02-06-update-on-developer-access-and-platform-security ; https://developer.spotify.com/documentation/web-api/tutorials/february-2026-migration-guide ; https://techcrunch.com/2026/02/06/spotify-changes-developer-mode-api-to-require-premium-accounts-limits-test-users/ ; quota update https://developer.spotify.com/blog/2026-07-23-web-api-quota-updates
- Spotify Free skip limits after Sept 2025: https://techcrunch.com/2025/09/15/spotify-will-now-let-free-users-pick-and-play-tracks/
- Prior art (local): `/home/user/ytm-andr/FINDINGS.md` (E8 playFromUri on live session; E9 cold-start failures; "Position is a snapshot"; "Advertised ≠ usable"), `/home/user/ytm-andr/YTMLauncher/CLAUDE.md` (Notification Access → getActiveSessions).
