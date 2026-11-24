# View 01: Work calendar (Outlook / Teams): feasibility

**Verdict: feasible, but not mainly from notifications.** Notifications can only tell you
about a meeting about 15 minutes before it starts (or once someone has joined it). They can't
give you "today's schedule". The workable route is **Outlook for Android's "Sync calendars"
toggle → Android `CalendarContract`**, which `CalendarSource` already reads. Notifications are
a useful *extra* for the "starting now / join" moment and a fallback signal.

Legend: **[V]** verified from a source linked here · **[A]** my assumption or Android knowledge,
not checked on a device · **[T]** must be tested on the S26 Ultra with the user's real work account.

Note: `learn.microsoft.com` was not reachable from the research session (network allowlist), so
Microsoft Learn pages are cited from search-result summaries, not read in full.

---

## 1. Outlook "Sync calendars" → CalendarContract

- **[V]** Outlook for Android can sync its calendars into the native Android calendar
  (Settings → *account* → *Sync calendars*). Admins can switch this off with the Intune app-config
  key "Sync Calendars" (Off / On / Not configured). It needs to be On or Not configured for events to reach
  the native app.
  ([Outlook for iOS/Android configuration with Intune](https://learn.microsoft.com/en-us/exchange/clients-and-mobile-in-exchange-online/outlook-for-ios-and-android/outlook-for-ios-and-android-configuration-with-microsoft-intune),
  [Q&A: sync setting in managed Outlook](https://learn.microsoft.com/en-us/answers/questions/2155521/share-personal-calendar-in-managed-outlook-on-pers))
- **[A]** The sync writes through Outlook's own sync adapter (an Android account of Outlook's type)
  into `CalendarContract.Calendars/Events`. So any app with `READ_CALENDAR` sees the events, including
  Samsung Calendar, Google Calendar and **Nightstand's `CalendarSource` with no code change**. This is
  the documented user-facing purpose of the toggle. I have no reason to think it changed in
  2025–26, but it's **[T]**.
- **[A]** If an Intune App Protection Policy is applied, the APP setting *"Sync policy managed app data
  with native apps or add-ins"* must also be **Allow**. Otherwise Outlook won't write org data to the
  provider.
- **[V]/[A] Work profile caveat.** If Outlook runs inside an **Android Enterprise work profile**
  (common for BYOD with Intune), its synced calendars live in the *work* user's calendar provider.
  A personal-profile app like Nightstand **cannot read it**. The work calendar is only visible
  cross-profile if the admin allowlists the package via `setCrossProfileCalendarPackages`, which
  is not the default
  ([AOSP commit summary](https://gitlab.e.foundation/e/os/android_frameworks_base/-/commit/e1a96063bcc5b112ada8fda2e148743c08a1a00f),
  [Google Workspace: personal & work calendars](https://workspaceupdates.googleblog.com/2022/09/google-endpoint-management-personal-work-calendars-android.html)).
  Workaround **[A]**: install a second copy of Nightstand in the work profile. That's awkward and may be
  blocked by policy.
- **[V]** Known quirk: duplicate events in Samsung Calendar when both Exchange ActiveSync and Outlook
  sync are active ([UW–Madison KB](https://kb.wisc.edu/microsoft365/page.php?id=46599)).
  Nightstand should de-dupe by (title, begin) anyway.
- **[A]** What arrives: title, start/end, all-day flag, location ("Microsoft Teams Meeting" for
  Teams invites), self-attendee status, calendar colour. The Teams join URL is probably *not*
  in `DESCRIPTION`, so joining goes through Outlook/Teams instead **[T]**.

## 2. What Outlook / Teams notifications contain

| Source | Notification | When | Likely contents **[A]/[T]** |
|---|---|---|---|
| Outlook | Event reminder | event's reminder time (default 15 min) | `EXTRA_TITLE` = subject, `EXTRA_TEXT` ≈ "11:00 – 11:45 · Microsoft Teams Meeting" (time + location), actions *Join* (Teams) / *Snooze* / *Dismiss*; `category` likely `event` or `reminder` |
| Outlook | New mail, invites | any time | sender / subject. An *invite* mail gives title + time in text form, but it is mail, not a schedule |
| Teams | "Meeting started" | when someone joins a meeting on your calendar (on by default) **[V]** ([Teams mobile default notifications](https://support.microsoft.com/en-us/office/troubleshoot-notifications-for-teams-mobile-apps-6d125ac2-e440-4fab-8e4c-2227a52d460c)) | meeting title, "X started the meeting", *Join* action |
| Teams | Incoming call | live | `category=call`, CallStyle, caller name. No schedule value |
| Teams | Chat / channel / meeting chat | any time | sender + message. Noise for this view |

- **[V]** Teams-meeting reminders in Outlook carry a **Join** affordance when the invite has Teams
  metadata. Forwarded or external invites sometimes lose it
  ([Microsoft support: Outlook + Teams meetings](https://support.microsoft.com/office/does-outlook-work-with-skype-for-business-microsoft-teams-meetings-4e02264a-6d6e-4d14-8045-6f02b2b25441),
  [community thread](https://techcommunity.microsoft.com/discussions/microsoftteams/no-join-button---teams-outlook-add-in/1117666/replies/1153129)).
- **[V] Company policy can blank the content:** Intune APP "Org data notifications = Block org data"
  hides subjects in Outlook mail *and calendar* notifications unless the admin also allows calendar
  notifications via app config
  ([Tech Community: limiting sensitive data in notifications](https://techcommunity.microsoft.com/blog/intunecustomersuccess/limiting-sensitive-data-in-notifications/1009778),
  [inthecloud247 walkthrough](https://www.inthecloud247.com/limit-sensitive-data-in-outlook-mobile-notifications/)).
- **[A]** The exact extras layout and the text format (locale-dependent: "11:00 AM – 11:45 AM",
  24 h, German, etc.) are undocumented. A parser must be regex-on-text, tied to one Outlook version, and fail soft.
  The time is in *text* only. `postTime`/`when` is the reminder's post time, not the event start.
- **Big gap:** reminders appear only ~15 min ahead (or at start). There is **no future schedule**, no
  "today at a glance", and nothing is shown after the user dismisses the reminder on another device.
  Notifications alone give a weak view: at best a **"starting soon / now" banner**.

## 3. Microsoft Graph (`/me/calendarView`) with MSAL

- Delegated `Calendars.Read` (or `Calendars.ReadBasic`) via MSAL Android, an own Entra app
  registration (multi-tenant, public client, redirect `msauth://com.nightstand/<sig-hash>`).
- **Consent is the blocker in work tenants.** Microsoft-managed default consent lets users
  consent only to "low-risk" permissions (typically openid/profile/User.Read), and for multi-tenant apps from
  verified publishers. Many enterprises disable user consent entirely → **admin approval
  required** ([Entra: configure user consent](https://learn.microsoft.com/en-gb/entra/identity/enterprise-apps/configure-user-consent),
  [manage app consent policies](https://learn.microsoft.com/en-us/entra/identity/enterprise-apps/manage-app-consent-policies),
  [Q&A: admin approval for Calendars.ReadBasic](https://techcommunity.microsoft.com/discussions/teamsdeveloper/asking-for-admin-approval-for-onlinemeetings-read--calendars-readbasic-while-it-/4200946)).
  An unverified personal publisher makes user consent even less likely **[A]**.
- Conditional Access (compliant device / approved client app) can also block an unknown client **[A]**.
- Reusing a Microsoft first-party client ID to sidestep consent is technically possible, but it's a policy
  violation and fragile. **Not recommended.**
- Pros: full, exact data (join URL `onlineMeeting.joinUrl`, response status, organizer), works
  from a work profile. Cons: Azure app registration, MSAL + token cache, network + refresh
  scheduling, likely admin ticket. Too heavy for a personal sideloaded app unless the user *is* the admin.

## 4. Other routes

- **Samsung Calendar / Android "Exchange" account (EAS):** events land in `CalendarContract`
  directly. But many tenants use Conditional Access "Outlook mobile only", which **blocks
  native EAS clients** ([Securing Outlook for iOS and Android](https://learn.microsoft.com/en-us/exchange/clients-and-mobile-in-exchange-online/outlook-for-ios-and-android/secure-outlook-for-ios-and-android)).
  Works only if the tenant still allows EAS. Same work-profile caveat applies.
- **Google Calendar app:** it can't add an Exchange account itself. It only displays what is already in
  the provider (via Outlook sync or EAS). Nothing new.
- **ICS "Publish calendar" link (Outlook on the web → Settings → Calendar → Shared calendars → Publish):**
  - Pros: no app registration, no policy on the phone. Plain HTTPS GET of an `.ics` every
    10–15 min, parsed in-app (or via a tiny ICS parser). Works regardless of work profile.
  - Cons: **often disabled by admins** (the option disappears when the sharing policy lacks the
    Anonymous domain) ([Microsoft KB: Publish Calendar option missing](https://learn.microsoft.com/en-us/troubleshoot/exchange/outlook-on-the-web-issues/publish-calendar-option-missing-from-calendar-settings),
    [Q&A: IT blocked ICS](https://learn.microsoft.com/en-us/answers/questions/5699139/how-to-get-the-url-or-ics-of-a-calendar)).
    It's an **unauthenticated secret URL** to your work calendar, which may break company data policy even
    where it's technically allowed. Publishing "Can view all details" is needed for titles. Server-side
    refresh lag of minutes is reported **[A]**. Some users report 500 errors from published links.
  - **[A]** Recurrence (RRULE/EXDATE/RECURRENCE-ID) must be expanded in-app, so you need a library
    (e.g. ical4j: big) or a careful minimal implementation.

## Ranked options

| # | Option | Data quality | Setup effort | Policy risk | Notes |
|---|---|---|---|---|---|
| **1** | **Outlook "Sync calendars" → CalendarContract** | Full day schedule, live | Flip one toggle; **zero new code** for data | Blocked if Intune sets Sync Calendars Off / APP blocks native sync, or Outlook is in a work profile | Best by far when it works |
| 2 | Notification projection (Outlook reminder, Teams "meeting started") | Only next meeting, ~15 min ahead; text parsing | Already have listener access; parser work | Blanked by "Block org data" | Good *complement* for the "Join now" moment, weak as the only source |
| 3 | ICS publish URL | Full schedule, minutes of lag | Paste URL; network fetch + ICS/RRULE parser | Often disabled; secret URL leaks the calendar | Fallback when #1 is blocked by work profile/policy |
| 4 | Native Exchange account (Samsung) | Full, live | Account setup | Usually blocked by "Outlook-only" CA | Same provider path as #1 |
| 5 | Graph + MSAL | Best (join URLs etc.) | High: app registration, auth, refresh | Admin consent almost certainly needed | Only if the user is a tenant admin |

## Recommendation

1. **Primary: CalendarContract.** Ask the user to turn on Outlook → account → *Sync calendars*.
   The Work page then shows the next meeting + today's agenda from `CalendarSource`, *filtered to the
   work account's calendars* (pick calendars in settings by `ACCOUNT_NAME`/`ACCOUNT_TYPE`). A meeting is
   "Teams" when `EVENT_LOCATION` contains "Microsoft Teams" or `DESCRIPTION` contains `teams.microsoft.com`.
2. **Overlay: notifications.** From `NotificationFeed`, pick up Outlook reminders and Teams
   "meeting started" for the matching event. Use them to flip the hero to **"Starting now · Tap to join"**
   and to fire the reminder's *Join* `PendingIntent` on tap (needs unlock: `requestDismissKeyguard`).
   If no synced calendar exists, show a degraded "next meeting from reminder" card.
3. **Fallback: ICS URL** (setting field), only if #1 is impossible for the user's tenant.
4. Skip Graph unless the user can grant admin consent themselves.

## Open questions for the user

1. Is your work Outlook on the phone **in a work profile** (briefcase icon / separate "Work" tab)
   or the normal personal space? This decides whether #1 can work at all.
2. In Outlook → Settings → your work account: is **"Sync calendars"** there and can you turn it on?
   After that, do the meetings appear in Samsung Calendar?
3. Do Outlook reminders on your phone show the **meeting subject** or a generic "You have a meeting"?
4. Is "Publish calendar" available in Outlook on the web for your work account, and would company
   policy allow using it?
5. Should the page show only work calendars, or merge personal ones (the main clock page already shows those)?
6. Should tapping "Join" actually unlock and launch Teams, or only show the info?

## Implementation plan (later)

- **`WorkCalendarPage : View`** (plain Canvas View like `NightstandView`). Hosted in the horizontal
  pager. API: `var night: Boolean`, `fun tick(now: Long)` called once per minute,
  `fun setData(next: WorkEvent?, today: List<WorkEvent>, live: MeetingSignal?)`.
  Layout per `mockup.html`: left = NEXT label + countdown, huge start time, title (ellipsized,
  auto-shrink), time range, source pill, join hint, "Now" progress bar. Right = today's agenda
  (past rows dimmed, current row accented, "+N more"). Night: every paint = `0xFFFF3B1F`, hierarchy
  only via alpha (100% / ~62% / ~28%). Day accents: orange NEXT, green NOW, Teams violet, Outlook blue.
- **`WorkCalendarSource`** (reuse/extend `CalendarSource`): query `Instances` from start of today to
  end of tomorrow. Add `CALENDAR_ID, ACCOUNT_NAME, ACCOUNT_TYPE, EVENT_LOCATION, DESCRIPTION` to the
  projection. Filter by user-selected calendar IDs (stored in `Prefs`). Derive `isTeams`, `joinUrl?`
  (regex on description). Register a `ContentObserver` on `CalendarContract.CONTENT_URI` to refresh.
  Permission: existing `READ_CALENDAR`.
- **`MeetingNotificationParser`** (pure Kotlin, unit-tested): input = pkg
  (`com.microsoft.office.outlook`, `com.microsoft.teams`), `EXTRA_TITLE`, `EXTRA_TEXT`,
  `EXTRA_SUB_TEXT`, `category`, `channelId`, action titles. Output = `MeetingSignal(title, startText?,
  kind = REMINDER|STARTED, joinAction: PendingIntent?)`. Extend `NotificationFeed` to keep these
  (it currently drops everything but the title). Match to calendar events by fuzzy title within ±30 min.
  First step: **dump real notification extras on the device** (debug screen) before writing regexes.
- **Optional `IcsSource`**: `WorkManager` periodic fetch (15 min) + minimal ICS/RRULE parser, cached
  to a file. Needs `INTERNET` permission (new for this app).
- **Settings**: calendar picker (work calendars), "use Outlook/Teams notifications" toggle, optional ICS URL.
- **Tests**: parser fixtures from real captured extras (several locales/12 h/24 h), agenda
  selection (past/now/next), de-dupe Outlook-sync vs EAS duplicates.

## Mockups

`mockup.html` (sample time Tue 24 Nov, 10:35), rendered at 915×412 CSS px @2x:
`mockup-day.png`, `mockup-night.png`.
