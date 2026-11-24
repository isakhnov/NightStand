# View 03 — Photos (photo frame page)

Target: iPhone StandBy's Photos view. Full-bleed photos change slowly with a Ken Burns
pan and a crossfade, and a small clock and date sit bottom-left. The user wants the
source to be **Google Photos: Favorites ("starred"), all photos, or one album**.

Mockups: `mockup.html` (open with `?night` for the night version), `mockup-day.png`,
`mockup-night.png` (915×412 CSS px @2x, rendered with Playwright; the landscape is
generated SVG).

## Verdict

**There is no supported way to keep a live, auto-refreshing feed of the user's Google
Photos Favorites, whole library or an album in a third-party app, as of November 2026.**
Google removed that ability on 31 March 2025. Everything still possible is either:

- **a snapshot:** the user picks photos once, and we copy them, or
- **on-device only:** photos stored on the phone, read through MediaStore.

The best result combines two sources:

1. **"Picked from Google Photos"**: Android's system photo picker. With Google Photos
   set as its cloud provider, it shows Google Photos albums and Favorites. We copy the
   picked photos into app storage. No Google Cloud project is needed.
2. **"On this phone"**: MediaStore. Choose all photos, Favorites (`IS_FAVORITE`, if the
   gallery app sets it) or a folder ("album"). This source refreshes itself.

The work is moderate (about 2–3 days for the page and its setup). The risks are known.

---

## 1. Google Photos Library API after 31 March 2025 — VERIFIED

Google's update notice says the `photoslibrary.readonly`, `photoslibrary.sharing` and
`photoslibrary` scopes were removed. Calls that rely on them return
`403 PERMISSION_DENIED`. The remaining scopes are `photoslibrary.appendonly`,
`photoslibrary.readonly.appcreateddata` and `photoslibrary.edit.appcreateddata`.
Quote: *"You can now only list, search, and retrieve albums and media items that were
created by your app."* Google points apps that need to pick photos to the Picker API.

So your belief is **confirmed**. An app cannot list the user's library, albums or
Favorites. Tools such as MagicMirror's MMM-GooglePhotos and rclone hit the same wall
(see the issue and forum threads below).

Sources:
- [Updates to the Google Photos APIs (developers.google.com)](https://developers.google.com/photos/support/updates)
- [Google Developers Blog: Picker API launch and Library API changes](https://developers.googleblog.com/pt-br/google-photos-picker-api-launch-and-library-api-updates)
- [Access app-created media items](https://developers.google.com/photos/library/guides/access-media-items)
- [MMM-GooglePhotos issue #194](https://github.com/hermanho/MMM-GooglePhotos/issues/194) · [rclone forum: Picker API](https://forum.rclone.org/t/add-the-new-google-photos-picker-api-to-rclone/47938)

## 2. Google Photos Picker API (REST) — mostly VERIFIED

How it works (verified):
- `sessions.create` returns a `pickerUri`. The user opens it, which hands off to Google
  Photos, and picks items there. The app then polls `sessions.get` (using
  `pollingConfig.pollInterval` and `timeoutIn`) until `mediaItemsSet` is true, and calls
  `mediaItems.list?sessionId=…`.
- Each session has an `expireTime`. The `pickerUri` cannot be opened in an iframe.
- `baseUrl` links **stay valid for 60 minutes** and can stop sooner if the user revokes
  access. They are **not long-lived**, so the app must download the bytes, for example
  `baseUrl=w2560-h1440`, during that window.
- The scope is `photospicker.mediaitems.readonly`.
- Albums and Favorites are **not browsable** in the Picker. The user can **search** by
  album title and then select **individual items** from the results. There is no option
  to "subscribe to an album".

Consequences for this app:
- **Picking an album once and refreshing it later is not possible.** Each session is a
  one-off selection. New photos added to the album later are not seen, so the user has
  to pick again.
- It needs a **Google Cloud project, an OAuth client and a consent screen** (verified).
- **"Testing" mode:** a test user's grant and refresh token **expire 7 days after
  consent** (verified, Google Cloud help, "Manage App Audience"). That rules out using
  it for months. The fix is to set the app to **"In production" without verification**.
  This is allowed for a personal app: it shows the "unverified app" warning and a
  100-user cap. Forum reports say you must re-consent after switching (assumption: we
  only need the token when the user picks again, so long-lived tokens hardly matter if
  we copy the files).
- It has no advantage over the system photo picker (section 3) except possibly a larger
  selection size. It costs much more: OAuth, a Cloud project, network code and polling.

Sources:
- [Picker: Create and manage sessions](https://developers.google.com/photos/picker/guides/sessions)
- [Picker REST: sessions](https://developers.google.com/photos/picker/reference/rest/v1/sessions)
- [Picker: List and retrieve media items (60-min baseUrl)](https://developers.google.com/photos/picker/guides/media-items)
- [Picker: what users see (albums/favourites not shown, search only)](https://developers.google.com/photos/picker/guides/picking-experience)
- [Google Cloud help: Manage App Audience (Testing = 7-day expiry)](https://support.google.com/cloud/answer/15549945)
- [Google group thread on 7-day refresh tokens](https://groups.google.com/g/adwords-api/c/Z_kihrf6VCE)

Not verified, because developers.google.com could not be reached from this sandbox and
the search snippets do not answer it: the exact `pickingConfig.maxItemCount` limit (I
believe 2000), and how the `pickerUri` hands off to the Google Photos app on Android.

## 3. On-device options

### 3a. Android system photo picker plus Google Photos as cloud provider — VERIFIED / PARTLY ASSUMED
- Verified (Android Developers Blog, Feb 2024): backed-up "cloud photos" from the
  selected cloud media app (**Google Photos supports this**) are merged into the system
  photo picker. *"Any albums you've created in your cloud storage app will be readily
  accessible within the photo picker's albums tab"*, and the provider's **Favorites** are
  shown there too. The picker needs **no runtime permission**.
- Verified: the photo picker docs show
  `takePersistableUriPermission(uri, FLAG_GRANT_READ_URI_PERMISSION)`. The AOSP
  MediaProvider commit says picker URIs carry the persistable flag "across app/device
  restarts". There is a known **limit of about 128 persisted grants per app**
  (issuetracker 149315521, which applies to the SAF/persisted-grant table, so treat it as
  a real risk).
- Assumed, to test on the S26: reading a **cloud-only** item's URI makes the picker's
  provider fetch it from Google Photos over the network. A persisted grant could break if
  the user changes the cloud provider or the item is deleted. **Plan: copy each picked
  image once into app storage** (downscaled to about 2560 px on the long edge, around
  1 MB each), then drop the grant. This also avoids the 128-grant cap. For 200 photos
  that is about 200 MB.
- Limits: the user picks **items**, not "the whole album, live". The multi-select limit
  is `MediaStore.getPickImagesMaxLimit()` (assumption: about 100 on current builds), and
  there is no "select all". The selection is a snapshot; adding more means opening the
  picker again.
- Assumed, to check on One UI 9: Samsung ships Google's photo picker (the
  MediaProvider/Photopicker mainline module) and lets the user choose Google Photos under
  picker settings → "Cloud media app".

Sources:
- [Android Developers Blog: Cloud photos now available in the Android photo picker](https://android-developers.googleblog.com/2024/02/cloud-photos-now-available-in-android-photo-picker.html)
- [Photo picker guide (persisting access)](https://developer.android.com/training/data-storage/shared/photo-picker)
- [AOSP MediaProvider commit 29998120 (persistable picker URIs)](https://android.googlesource.com/platform/packages/providers/MediaProvider/+/29998120e)
- [Issue tracker 149315521 (128 persisted grants)](https://issuetracker.google.com/149315521)
- [Create a cloud media provider](https://developer.android.com/guide/topics/providers/cloud-media-provider)

### 3b. MediaStore (photos stored on the phone) — VERIFIED API / UNKNOWN vendor behaviour
- `READ_MEDIA_IMAGES` (Android 13+) reads every image on the device, including
  DCIM/Camera, Screenshots and downloads. On Android 14+ the user may choose
  **"Allow limited access"** (`READ_MEDIA_VISUAL_USER_SELECTED`). Then only the chosen
  items are visible, and we should detect that and explain it. For a sideloaded personal
  app, full access is fine.
- "Albums" means the **folders** (`BUCKET_DISPLAY_NAME`). Samsung Gallery albums are
  mostly folders, so they map well. Google Photos albums are cloud-only and do not map.
- **Favorites:** `MediaStore.MediaColumns.IS_FAVORITE` exists from API 30, and you can
  filter with `QUERY_ARG_MATCH_FAVORITE` (verified, AOSP MediaProvider docs).
  **Whether Samsung Gallery's heart or Google Photos' star writes this column is
  unverified.** I found no source either way. My assumption: Samsung Gallery keeps its
  favourites in its own private database, and Google Photos may call
  `MediaStore.createFavoriteRequest` for local copies. **This has to be tested on the
  phone:**
  `adb shell content query --uri content://media/external/images/media --projection _id:_display_name:is_favorite --where "is_favorite=1"`
  Do this after hearting one photo in Samsung Gallery and starring another (local) one in
  Google Photos.
- Photos whose local copy was removed by Google Photos "Free up space" are not in
  MediaStore.
- No permission or API reaches Google Photos' private cache.

Sources:
- [MediaStore.MediaColumns reference (IS_FAVORITE)](https://developer.android.com/reference/android/provider/MediaStore.MediaColumns)
- [AOSP: MediaProvider module (is_favorite, QUERY_ARG_MATCH_FAVORITE)](https://source.android.com/docs/core/media/media-provider)
- [Access media files from shared storage](https://developer.android.com/training/data-storage/media)

## 4. Other options

- **A synced local folder:** the user downloads a Google Photos album (Download all, as
  a zip) into a folder, or syncs one with Syncthing, Drive or FolderSync. The app reads
  that folder via MediaStore buckets or a SAF tree URI (`ACTION_OPEN_DOCUMENT_TREE`,
  persistable). This is robust and refreshes itself, but it is not linked to Google
  Photos. Google offers no automatic Google Photos → device folder sync, and rclone lost
  read access in 2025.
- **Shared-album link** (`photos.app.goo.gl/...`): it can only be read by scraping the
  undocumented web page. That is fragile and possibly against the Terms of Service.
  **Not recommended.**
- **Google Takeout:** a manual, one-off export. Only for a first bulk import.

## Ranking (closest to "starred / all / album from Google Photos")

| # | Option | Starred | All | Album | Auto-refresh | Effort | Main risks |
|---|--------|---------|-----|-------|--------------|--------|-----------|
| 1 | **System photo picker (Google Photos cloud) → copy to app storage** | ✔ pick from Favorites tab | ✖ (only what is picked) | ✔ pick from album, item by item | ✖ re-pick | **Low** (~1 day) | Picker max-items limit; One UI must show the Google Photos cloud tab |
| 2 | **MediaStore on-device** (all / IS_FAVORITE / folder) | ? depends on IS_FAVORITE test | ✔ local photos | ✔ folders (Samsung albums) | ✔ ContentObserver | **Low** (~1 day) | IS_FAVORITE may not be set; "free up space" items missing |
| 3 | Google Photos Picker API (REST) | search only | ✖ | search by album title, pick items | ✖ re-pick | **High** (3–4 days, OAuth + Cloud project) | 60-min URLs, token expiry, more moving parts, no gain over #1 |
| 4 | Synced folder (Syncthing/Drive/manual download) | ✖ | ✖ | ✔ (by export) | ✔ if synced | Low | Manual step outside the app |
| 5 | Shared-album scraping | ✖ | ✖ | ✔ | ✔ | Medium | Unofficial, breaks silently, ToS |

## Recommendation

Build **#1 and #2 behind one "Photo source" setting**:

- **Google Photos (picked)** (default): "Choose photos…" opens the system picker in
  multi-select. The user opens the Albums tab → Favorites, or one of their Google Photos
  albums, and selects. We copy and downscale the photos into `filesDir/photos/`. "Add
  more…" and "Replace…" re-open the picker. This is the honest version of "starred or
  album": a snapshot the user curates.
- **On this phone**: All photos / Favorites / one folder, read live from MediaStore.
  Show "Favorites" only if a quick test query finds `is_favorite=1` rows. Otherwise
  explain that the gallery app doesn't share favourites.
- Skip the REST Picker API unless the on-device picker turns out to hide Google Photos
  cloud albums on One UI 9.

### Night and burn-in

- **Night (recommended, see `mockup-night.png`):** keep the photo, but show it
  **red-monochrome and dim**. A `ColorMatrix` maps luminance into the red channel only;
  green and blue stay near 0. Draw it at about 40% alpha under a heavy scrim, with the
  clock in the app's night red (`0xFFB3241B`, or `0xFFFF3B30` on boost). **No Ken
  Burns at night** (motion catches the eye in the dark), and change photos every
  10–15 min with a slow 3 s fade. This matches StandBy, whose Night Mode tints
  everything red.
- **Setting "At night: red photo | clock only"**: "clock only" draws black with the
  shared night clock. This is the most OLED- and sleep-friendly choice. Also offer
  "skip this page at night", which makes the pager jump to the clock page.
- **Burn-in:** photos change, so the image itself is low risk. The **overlay** is the
  static part. Reuse `NightstandView.shiftFor(minute, amplitude)` to drift the clock and
  date a few dp each minute, keep the overlay small, and avoid any always-white UI.
  Pager dots fade out after 3 s.
- **Day timing:** 30 s per photo by default (5 s–5 min), with a 1.5 s crossfade and a
  Ken Burns pass of 1.00→1.08 scale plus a small translate over the photo's dwell time.
  Pick a random direction per photo and bias the pan toward the center of the photo.

## Open questions for the user

1. Are your favourites/albums mostly **cloud-only** in Google Photos, or also on the
   phone (camera photos, backed up)? This decides whether #2 alone is enough.
2. Do you heart photos in **Samsung Gallery** or star them in **Google Photos**? Please
   run the `adb ... is_favorite=1` query above after starring one, or let me do it during
   on-device testing.
3. Is a **snapshot** you re-pick now and then acceptable? A live "album follows Google
   Photos" feed is impossible without a sync tool.
4. Storage budget for copied photos (default: max 300 photos, about 2560 px, roughly
   300 MB)?
5. Night: red-tinted photo (as mocked), clock only, or skip the page?
6. Portrait photos: crop to fill (iOS crops to fill, using faces/saliency), or blur-fill
   the sides? Default: crop to fill, centered on the upper third.

## Implementation plan (later)

The page is an Android `View` inside the horizontal pager. It has `var night: Boolean`
and `fun onMinuteTick(now)`.

**Classes (package `com.nightstand.photos`):**
- `PhotoSource` (interface): `suspend fun list(): List<PhotoRef>`, plus `observe(onChange)`.
  - `PickedPhotoSource`: files in `filesDir/photos/` plus a small JSON index (original
    URI, date taken, width and height, crop focus).
  - `MediaStorePhotoSource(mode = ALL | FAVORITES | BUCKET(id))`: a `ContentResolver`
    query with `QUERY_ARG_MATCH_FAVORITE` / `BUCKET_ID`, plus a `ContentObserver` on
    `Images.Media.EXTERNAL_CONTENT_URI`.
- `PhotoImporter`: takes the picker result URIs, reads them with
  `ImageDecoder.createSource(contentResolver, uri)` and `setTargetSize` (≤2560 long
  edge, handling EXIF rotation), writes JPEG q85 to `filesDir/photos/<hash>.jpg`, and
  never keeps a URI grant. Runs on a background thread with progress shown in setup.
- `PhotoPlaylist`: a shuffled order with no repeats until every photo has been shown,
  persisted in `Prefs` so a restart doesn't replay the same photos.
- `PhotoFrameView : View`: two `Bitmap` slots (current and next), drawn with a `Matrix`
  for Ken Burns (`ValueAnimator`, or `postInvalidateOnAnimation` only while
  `isAttachedToWindow && isShown`). Crossfade by alpha. Bottom-left gradient scrim from a
  `LinearGradient` shader, then the clock and date (bold, tabular figures) drawn as in
  `NightstandView`. At night: `ColorMatrixColorFilter` red-mono at alpha about 0.4, no
  motion, longer interval. Decode the next bitmap ahead of time in the background, sized
  to the view (915×412 dp ×3.0 ≈ 2745×1236 px; inSampleSize/target size to match).
  Recycle/null old bitmaps; keep at most 2 in memory (about 27 MB at ARGB_8888; consider
  `HARDWARE` config).
- `PhotoSettingsActivity` (or a section in `SetupActivity`): source radio buttons,
  "Choose photos…", a thumbnail count and "Clear", interval, and the night behaviour
  setting.

**Permissions and manifest:**
- Picked source: **none** (`ActivityResultContracts.PickMultipleVisualMedia(maxItems)`
  with `ImageOnly`). On API 30–32 the AndroidX contract falls back to the
  Play-services-backported picker or `ACTION_OPEN_DOCUMENT`. That is fine, because the
  S26 is on 17.
- MediaStore source: `READ_MEDIA_IMAGES`, plus `READ_MEDIA_VISUAL_USER_SELECTED`
  (declared so Android 14+ partial access is handled cleanly), plus
  `READ_EXTERNAL_STORAGE` with `maxSdkVersion=32` for minSdk 30.
- No INTERNET permission is needed for the recommended path. The cloud download is done
  by the system picker's provider.

**Setup flow:** Settings → Photos page → source:
- "Google Photos (choose photos)" → the system picker opens. Hint text: "Open
  *Albums* → *Favorites* or an album, then select photos." → import progress → done.
- "Photos on this phone" → runtime permission → All / Favorites / Folder list built from
  a `BUCKET_DISPLAY_NAME` query with counts.
- If the source is empty, the page shows a black screen with the clock and a one-line
  hint.

**Testing:** unit tests for `PhotoPlaylist` (no repeats, persistence), Ken Burns matrix
math (the image always covers the view), and the luminance→red matrix. On the device:
the picker's cloud tab on One UI 9, the `is_favorite` query, memory use over 8 hours,
and battery use with the page always on.
