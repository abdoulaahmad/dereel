# Compatibility matrix (FR-38)

**Status for build 0.1.0: nothing below is verified on a physical device yet.** No feature may be
described as verified until a row lists the exact environment and a passing test record (SRS
10.1). The in-app Help screen shows the same state ("Not yet verified on a device").
Informal sessions on a phone and an emulator are recorded below as observations. They are not
verification.

The additions from scope change S-02 (protection alert and check, interval timer, sleep schedule,
live timer, redesigned screens) are unit-tested, were tried on an emulator (20 and 21 Nov 2026) and
were used once on the owner's Pixel 9 with the YouTube app (21 Nov 2026, "Time rules on the phone"
below). The alert notification was only seen on the emulator.

Emulator run (20 Nov 2026, Android 17 emulator, YouTube app signed out, Dereel debug build):

| Step | Result |
|---|---|
| Install over the previous build (database schema 1 to 2) | Dereel started, and the saved platforms and opening methods were unchanged |
| Home, a platform screen and Settings in the new design | Rendered as designed. Four platform cards fit on one screen. |
| "Check protection" | Listed live results: access on, service responding, service set up for every supported app, each app's state, browser ready, alerts off |
| Sleep schedule on, during its hours, then open Shorts in the YouTube app | Denied, Back after 177 ms, banner "Shorts are off until 7:00 AM. Your sleep schedule is on." |
| Sleep schedule off, 5-minute interval timer on YouTube, then open Shorts | Allowed, with the live timer "4:54 until break" drawn over the app and counting down |
| Time-entry and interval dialogs | Opened and saved |

Second emulator run (21 Nov 2026, same emulator, first-run setup with YouTube opened inside
Dereel, accessibility service switched on and off through `adb`):

| Step | Result |
|---|---|
| Turn on "Alert me if protection stops" | Android's notification permission prompt appeared at that moment, not before |
| Switch Dereel's accessibility service off | About 8 seconds later the notification "Dereel protection has stopped. Short videos aren't being blocked in your apps right now. Tap to see why and fix it." was posted |
| Switch the service back on | The notification was withdrawn |
| Tap the notification with Settings open in Dereel | First build tried: Dereel reopened on Settings. Changed, then tried again: it opens Home. |
| Home with the service off and YouTube set to open inside Dereel | First build tried: "Protection is active", with no row for the app. Changed (A-38), then tried again: "Some protection needs your attention", and a "Needs attention" row for the YouTube app with "Fix protection". |
| 5-minute interval timer, then a Shorts link shared to Dereel | The page opened in Dereel's browser with "4:54 until break" under the title, counting down |

Not tried anywhere: TalkBack and large text on the new screens, and the alert on a phone.

Third emulator run (22 Nov 2026, same emulator, fresh first-run setup with Instagram, YouTube and
Facebook opened inside Dereel; scope change S-04):

| Step | Result |
|---|---|
| First-run setup, Home, Activity, Settings, a platform screen and Help | Each platform shown with its logo, in the light and dark themes |
| Settings | Five cards. "Your apps" listed "Blocking Reels · 15 min a day" for Instagram and "Blocking Shorts" for YouTube. Accessibility access showed an "Off" pill. |
| Tap an app row in Settings, then Back | Its screen opened, and Back returned to Settings |
| Sleep schedule switched on | "Starts 10:00 PM" and "Ends 7:00 AM" appeared under the switch, and Home showed the schedule banner |
| "Dereel browser data" | Expanded to Instagram, YouTube and Facebook, each with "Clear" |
| "Remove Snapchat from Dereel" | The screen closed and Settings listed Snapchat as "Not added" with "Add" |
| "Add" on that row | Snapchat was added and its screen opened |

Not tried in that run: the "On" and "Not responding" states of the Accessibility pill, a
confirmation dialog for clearing browser data, TalkBack and large text. Nothing of S-04 has been
seen on a phone.

Fourth emulator run (22 Nov 2026, same emulator and setup as the third; scope change S-05):

| Step | Result |
|---|---|
| Home with three working methods | Teal status card "Protection is active" with a "Check protection" button, tiles "Today 0 sec" and "Last 7 days 0 sec", then one compact card per app |
| Dark theme, and 150% text size | Readable in both. At 150% nothing was cut off. |
| 30-minute allowance for YouTube, then a Shorts link shared to Dereel, left open for about a minute | The browser showed "29:48 left today" counting down. Home then showed "Today 1 min", "Last 7 days 1 min" with today's bar raised, and "1 min of 30 min used today" on the YouTube card. Activity showed the same 1 min. |
| Tap the "Today" tile | Activity opened |
| Add Snapchat (its app is not installed on the emulator) | The status card changed to "Some protection needs your attention", and the Snapchat card showed "Unavailable" with the reason |

Not tried in that run: TalkBack, a break or sleep schedule in force together with the new tiles,
and a week with time on several days. Nothing of S-05 has been seen on a phone.

## Rule sets

| Method | Rule set | Location | Fixtures |
|---|---|---|---|
| Instagram app | `ig-native-0.1.0` | `core/.../nativeapp/NativeRules.kt` | Synthetic trees in `NativeDetectionTest` |
| YouTube app | `yt-native-0.1.0` | `core/.../nativeapp/NativeRules.kt` | Synthetic trees in `NativeDetectionTest` |
| Instagram inside Dereel | `ig-web-0.1.0` | `app/src/main/assets/rules/instagram.js` + `core/.../web/WebRoutes.kt` | `route-fixtures.json`, `tools/rules-tests` |
| YouTube inside Dereel | `yt-web-0.1.0` | `app/src/main/assets/rules/youtube.js` + `core/.../web/WebRoutes.kt` | `route-fixtures.json`, `tools/rules-tests` |
| Facebook inside Dereel | `fb-web-0.1.0` | `app/src/main/assets/rules/facebook.js` + `core/.../web/WebRoutes.kt` | `route-fixtures.json`, `tools/rules-tests` |
| Facebook Lite app | `fb-lite-native-0.1.0` | `core/.../nativeapp/NativeRules.kt` | Hand-built trees in `NativeDetectionTest`, using identifier names and positions read from a device |
| Snapchat app | `sc-native-0.1.0` | `core/.../nativeapp/NativeRules.kt` | Hand-built trees in `NativeDetectionTest`, using identifier names and positions read from a device |

Not offered, so there is nothing to verify: the regular Facebook app (D-11), and Snapchat inside
Dereel (Snapchat has no mobile website).

Any change to an identifier, selector or route rule must bump the rule-set version and update the
matching fixture.

## Native anchors still to verify

| App | Anchor (view ID) | Expected meaning | Observed on |
|---|---|---|---|
| Instagram | `clips_viewer_view_pager` | Full-screen Reels viewer | Pixel 9, Android 17, Instagram 456.0.0.53.78: matched once (informal session, 20 Nov 2026) |
| Instagram | `clips_tab` (selected) | Reels tab active | — |
| Instagram | `tab_bar`, `feed_tab`, `search_tab`, `profile_tab`, `direct_tab` | Layout recognized / navigation region | — |
| YouTube | `reel_recycler` | Shorts player | Emulator smoke: YouTube 20.10.41, Android 17 (API 37), signed out, English — matched for the Shorts tab and a Shorts deep link |
| YouTube | `reel_player_page_container` | Shorts player | — |
| YouTube | `pivot_bar` | Layout recognized / navigation region | Same emulator run: cover left the bottom navigation uncovered |
| Facebook Lite | `video_view` (at least 60% of the window) | Full-screen video player, used for Reels | Pixel 9, Android 17, Facebook Lite 539.0.0.7.106 (informal session, 20 Nov 2026): matched by Dereel while a reel was playing |
| Facebook Lite | `main_layout` | Layout recognized | Same session: present on the feed and the player. One later screen reported no anchors. |
| Snapchat | `spotlight_container` | Full-screen Spotlight feed | Pixel 9, Android 17, Snapchat 14.32.0.54 (informal session, 20 Nov 2026): matched by Dereel each of the five times the Spotlight tab was opened, and never on the Stories, Map, Chat or Camera tabs |
| Snapchat | `ngs_map_icon_container`, `ngs_chat_icon_container`, `ngs_camera_icon_container`, `ngs_community_icon_container`, `ngs_spotlight_icon_container` | Layout recognized / navigation region (one anchor per tab, no bar) | Same session: layout reported as recognized on Camera, Stories, Chat and Spotlight. One screen (after the Map tab) reported no anchors. A tap on the Camera tab went through while the cover was up, so the tabs were left uncovered. |

Emulator observations are smoke evidence, not verification. The reference device (D-02) must
repeat these, and also measure Dereel's effect on YouTube's responsiveness: YouTube showed ANR
dialogs on the saturated emulator.

## First physical-device session (20 Nov 2026)

Pixel 9, Android 17 (API 37), WebView 159.0.8154.18, Instagram app 456.0.0.53.78, YouTube app
21.45.82, English. Informal session with the owner; not a recorded test run.

| Observation | Result |
|---|---|
| Accessibility switch after installing from a file | Blocked by Android's restricted settings until "Allow restricted settings" was used. Installing with adb avoids it. |
| YouTube sign-in inside Dereel | Worked: `accounts.google.com` → `accounts.youtube.com` → YouTube, all inside Dereel. Google did not refuse this time (D-08 still open). |
| Instagram login inside Dereel | Failed repeatedly at Instagram's login-check page, then worked after the A-18 changes. Cause not isolated. |
| Native detection cost (debug build) | 3–110 ms per check, mostly under 35 ms, in the YouTube and Instagram apps |
| Instagram app Reels blocking | One observation: the Reels viewer was recognized by `clips_viewer_view_pager`, Dereel pressed Back once, and the screen was no longer Reels 125 ms later. No cover and no second Back. |
| Event-to-Back time (NFR-05) | 796 ms in that one observation (debug build), above the 750 ms target. The cause was a flat 500 ms wait before checking. The wait was changed afterwards. With the new wait, Snapchat's Spotlight gave 68 to 100 ms in three observations and Facebook Lite gave 78 ms in one (see those sessions). The Instagram and YouTube apps have **not** been re-measured. |
| YouTube app Shorts blocking | Not yet exercised on this phone |
| Another blocker installed | Scrolless's accessibility service was enabled at the same time. Results with two blockers active are not valid for Dereel. Later the same day Dereel's own service was found switched off with only Scrolless on, so app-side blocking seen in between cannot be credited to Dereel. The owner then turned Dereel on and Scrolless off before the Snapchat session. |

## Snapchat identifier capture (20 Nov 2026)

Pixel 9, Android 17. Read with `uiautomator dump`, keeping identifier names, classes and positions
only. Labels and text were counted, never printed, and the dump was deleted on the phone.

| Observation | Meaning for the rule set |
|---|---|
| The Spotlight tab has `spotlight_container` (a full-window `FrameLayout`) | Used as the short-video anchor |
| The same screen has `opera_viewer`, also full-window | Not used: it is Snapchat's general media viewer, so it would also match Snaps and Stories |
| Five `ngs_*_icon_container` tab anchors, no enclosing bar, none marked selected | The navigation region is the rectangle around them. The active tab cannot be read from them. |
| Many identifiers are reported as `0_resource_name_obfuscated` | Only the named ones can be anchors |
| After switching from Spotlight to Chat or Camera, `spotlight_container` was still listed about 2.5 s later and gone by about 6 s | **Not reproduced by Dereel's own detector** (see the session below). `uiautomator dump` waits for the screen to settle and can return cached nodes, so those timings were an artefact of the capture tool. |
| What Back does on the Spotlight tab | Leaves Spotlight and stays inside Snapchat (see below). Which tab it lands on was not recorded. |

## Snapchat on the phone with Dereel (20 Nov 2026)

Pixel 9, Android 17 (API 37), Snapchat 14.32.0.54, Dereel debug build with `sc-native-0.1.0`,
English. Dereel's Accessibility service was the only one enabled (Scrolless was off). Snapchat was
added in Dereel with blocking on, no allowance. Tabs were tapped through adb, and the results were
read from Dereel's debug log, which holds anchor names, decisions and timings only. Informal
session, not a recorded test run.

| Step | Result |
|---|---|
| Open the Spotlight tab (three separate times) | Recognized by `spotlight_container`, denied, one Back. Back was sent 100, 76 and 68 ms after the triggering event. The next check, 47 to 98 ms later, no longer saw Spotlight. No cover, no second Back. Snapchat stayed in front. |
| Open Spotlight a fourth time within 30 seconds | No Back (limit of three per 30 s). The cover was shown 81 ms after the triggering event and stayed while Spotlight was behind it. |
| Tap the Camera tab while the cover was up | The tap reached Snapchat. The next check saw no Spotlight and the cover was removed. |
| Open the Stories, Map, Chat and Camera tabs | No short-video detection, no Back and no cover on any of them (12 checks). |
| Detection cost | 6 to 56 ms per check |

Not covered in this session: a daily allowance or temporary access (so usage counting on Spotlight
and what happens when the user leaves by themselves while viewing is allowed), reaching Spotlight
by swiping or from a shared Spotlight link, other Snapchat versions and languages, and whether the
block banner and cover looked right on screen (only the log was read, the screen was not
captured). One log line from the first of four quick entries was missing from the phone's log
buffer, which Snapchat fills quickly; later runs streamed the log to the PC instead.

## Facebook Lite on the phone (20 Nov 2026)

Pixel 9, Android 17 (API 37), Facebook Lite 539.0.0.7.106, English. Names-only captures (view
classes, identifier names and positions; labels and text counted, never printed), then a Dereel
debug build with `fb-lite-native-0.1.0`. Informal session, not a recorded test run.

| Observation | Result |
|---|---|
| Home feed | 74 to 79 nodes. Three identifiers only: `root_layout`, `main_layout` and Android's `content`. No labels, no text. A scrolling list fills the area below the top bars. |
| A screen with a text field (probably comments) | 164 nodes, same three identifiers, one text field |
| Reels playing (opened by the owner) | 48 to 49 nodes and a fourth identifier: `video_view`, a `FrameLayout` at `[0,307][1080,2227]` (79% of the window) holding a `SurfaceView`, inside a full-height scrolling list. Captured twice. Two other attempts failed because the capture tool could not get a settled screen while video played. |
| Dereel on the Reels player | Recognized by `video_view`, denied, one Back 78 ms after the triggering event. The next check, 95 ms later, no longer saw the player. No cover. |
| Two minutes of the owner using Facebook Lite afterwards | 17 checks, no short-video detection, no Back. What was on screen during that time was not recorded, so this does not show that Stories or feed videos are safe. |
| Detection cost | 1 to 17 ms per check |

**A correction.** An earlier capture of the home screen alone led to the conclusion that Facebook
Lite gives an accessibility service nothing to work with. That was wrong: the video player has an
identifier.

Not yet known:

- whether `video_view` also fills the screen for a regular video, a live video or a video story.
  If it does, those are blocked as well, because nothing else distinguishes them;
- the real size of `video_view` for a video playing inside the feed (the unit test uses a made-up
  smaller size);
- where Back leaves the user;
- behaviour with an allowance or temporary access, in other languages and in other versions.

The regular Facebook app is not installed on this phone and was not examined.

## Time rules on the phone (21 Nov 2026)

One informal session on the owner's Pixel 9 (Android 17, build CP4A.261105.040.A1), YouTube app
21.45.82, signed in, Dereel debug build. Driven over `adb`. Results come from Dereel's debug log
(decision and reason names, anchor names, timings) and from screenshots of Dereel's own screens.
No screenshots of other apps were taken on purpose, so what Dereel's banner and timer look like
over the YouTube app was not looked at here: only that Dereel's overlay window was present.

| Step | Result |
|---|---|
| Install over the previous build (database schema 1 to 2), then two further updates | Dereel started each time with no crash. The four platforms, their opening methods and settings were unchanged, and the accessibility service reconnected by itself. |
| "Check protection" | Live results. Apps not opened since Dereel started were listed as "not checked yet", not as passed. |
| Sleep schedule on (10 PM to 7 AM) at about midnight, then the Shorts tab in the YouTube app | Denied. Back 81 to 102 ms after the trigger, reason `SLEEP_SCHEDULE`. Home showed "Sleep schedule is on. Short videos are off until 7:00 AM." |
| The same with a 5-minute interval window available | Still denied: the schedule outranks the timer |
| Schedule off, interval timer 5 minutes with a 15-minute break, Shorts tab | Allowed (`Allow(INTERVAL)`), with Dereel's timer window at the top of the screen |
| Stay on Shorts | The break started 5 min 0.06 s after the first allowed detection. Dereel pressed Back by itself, 46 ms after its timer fired. |
| Shorts tab during the break | Denied, Back after 86 ms. Home: "On a break until 12:36 AM". Activity: 5 min for YouTube. |
| A Shorts link shared to Dereel during the break | Dereel's browser showed "On a break from Shorts until 12:36 AM": the app and the browser share one window |
| Dereel updated during the break (process restart) | Still denied, reason `INTERVAL_BREAK` |
| Schedule switched on during the break | Reason became `SLEEP_SCHEDULE`. Browser message: "Shorts are off until 7:00 AM". |
| 27 seconds after the break's end time, Shorts tab | Allowed again with nothing changed in Dereel, and the timer window was back |
| Interval timer switched off, Shorts tab | Denied, reason `BLOCKED`, Back after 73 ms |
| Home pressed while an allowed Short was playing | **Gap.** YouTube kept the Short playing in a picture-in-picture window. Dereel did not count that time: after about 1.5 minutes the interval timer still showed "4 min until a break". Whether a break would stop it was not tried. |

Not tried on the phone: the alert notification, a daily allowance, time rules with Instagram,
Facebook Lite or Snapchat, the timer inside Dereel's browser, TalkBack and large text.

Two mistakes were made while driving the phone, both by the test commands and both undone:
"Block Shorts" for YouTube was off for about a minute, and the "Clear Facebook browser data"
confirmation was opened and dismissed without clearing anything. The cause was a test command
that stacked extra copies of Dereel's main screen, so taps reached a different screen than the
one last looked at. The main screen is now `singleTop`, so a second start reuses it.

## Facebook inside Dereel: what is and is not checked

| Part | State |
|---|---|
| Which addresses count as Reels or the Video tab | Unit-tested against shared fixtures in Kotlin and JavaScript. The address patterns come from Facebook's public link formats, not from a recorded session. |
| Navigation guard, signed out (emulator smoke, 20 Nov 2026, Android 17 emulator, m.facebook.com) | The sign-in page loaded with Dereel's rules and bridge present. Moving the page to `/reel/` and to `/watch/` was stopped with "Reels are blocked". An in-page change to `/reel/<id>/` was corrected back to the previous page. A named video (`/watch/?v=…`) was not stopped. A reel link shared to Dereel loaded nothing and showed the block screen. These moves were made by script, not by tapping Facebook's own buttons. |
| Sign-in page address | m.facebook.com sent a signed-out visitor to `/unified/login_via/app/`, which was first classified as a profile. It is now a sign-in route (`unified`). |
| Blocking after in-page navigation by Facebook itself | Relies on Facebook changing the page address when a reel opens. **Not yet observed signed in.** A reel that plays inside the feed without an address change is not blocked. |
| Hiding the Reels and Video shortcuts | CSS matches ordinary links (`a[href="/reel/"]`, `a[href="/watch/"]` and variants). The signed-out page on the emulator contained **no link elements at all**, only script-driven buttons, so the signed-in tabs may be built the same way. If they are, nothing is hidden and only the navigation is stopped. **Not yet observed signed in.** |
| Sign-in inside Dereel | Facebook signs in on its own hosts, which are allowed. **Not yet attempted.** |
| Messages | Facebook's mobile site may redirect to the Messenger app. Dereel never opens another app by itself. |

These are initial candidates. A maintainer should record, per app version: package version, Android
version, device, language, account type, and pass or fail for each journey below.

## Required journeys (release test cases)

Facebook Lite app (not in the table below): Reels player is denied ☐ · feed videos, Stories,
photos, Messenger links and Marketplace usable ☐ · a regular video opened full screen (expected to
be blocked, to be confirmed) ☐ · keyboard, system dialog, lock and app switch cause no false
session or loop ☐.

| Journey | Instagram app | YouTube app | Snapchat app | Instagram inside Dereel | YouTube inside Dereel | Facebook inside Dereel |
|---|---|---|---|---|---|---|
| Reels / Shorts / Spotlight tab is denied | ☐ | ☐ | ☐ | ☐ | ☐ | ☐ |
| Reel/Short opened from feed is denied | ☐ | ☐ | n/a | ☐ | ☐ | ☐ |
| Video tab is denied, a named video plays | n/a | n/a | n/a | n/a | n/a | ☐ |
| DMs / messaging usable | ☐ | n/a | ☐ | ☐ | n/a | ☐ (may need Messenger) |
| Camera, Stories and map usable | n/a | n/a | ☐ | n/a | n/a | n/a |
| No cover or Back after leaving Spotlight for another tab (D-12) | n/a | n/a | ☐ | n/a | n/a | n/a |
| Search usable | ☐ | ☐ | ☐ | ☐ | ☐ | ☐ |
| Profiles / channels usable | ☐ | ☐ | ☐ | ☐ | ☐ | ☐ |
| Regular videos usable | n/a | ☐ | n/a | n/a | ☐ | ☐ |
| Keyboard, system dialog, lock and app switch cause no false session or loop (AT-04) | ☐ | ☐ | ☐ | n/a | n/a | n/a |
| Direct link, in-page route, refresh, Back and Forward (AT-05) | n/a | n/a | n/a | ☐ | ☐ | ☐ |
| Shared reel from conversation: pause, resume, replay; advancing returns (AT-06) | not offered | not offered | not offered | ☐ | not offered | not offered |
| Allowance shared across methods (AT-08) | ☐ | ☐ | one method | ☐ | ☐ | one method |
| Sign in through the platform's own flow | n/a | n/a | n/a | ☐ | ☐ (see D-08) | ☐ |

## Best-effort filtering coverage (reported separately, FR-16)

| Control | Selectors or method | Tested coverage |
|---|---|---|
| Instagram Explore grid | `main a[href^="/p/"]`, `main a[href^="/reel/"]` on `/explore/` | Not yet measured |
| Instagram suggested posts | `article` containing the exact label "Suggested for you", "Suggested posts" or "Suggested reels" (English) | Unit-tested logic only |
| Instagram sponsored posts | `article` containing the exact label "Sponsored" or "Ad" (English) | Unit-tested logic only |
| YouTube Shorts tab | `ytm-pivot-bar-item-renderer:has(.pivot-shorts)` | Emulator smoke (m.youtube.com, signed out): matched and hidden |
| YouTube Shorts shelves | `ytm-reel-shelf-renderer`, `ytm-shorts-lockup-view-model(-v2)`, `grid-shelf-view-model:has(...)` | Emulator smoke: search results had 106 Shorts links, 0 visible |
| YouTube home feed | `ytm-browse ytm-rich-grid-renderer` on `/` | Emulator smoke: grid hidden, placeholder shown |
| YouTube up next | `ytm-item-section-renderer[section-identifier="related-items"]` and related | Emulator smoke: section present and hidden (12 cards, 0 visible) |
| YouTube promoted | `ytm-promoted-*`, `ad-slot-renderer` and containers | Not yet measured. Player video ads are out of scope. |
| Facebook Reels and Video shortcuts | `a[href="/reel/"]`, `a[href^="/reel/?"]`, `a[href="/reels/"]`, `a[href^="/reels/?"]`, `a[href="/watch/"]`, `a[href="/watch"]`, `a[href^="/watch/?ref"]` | Not yet measured. Needs a signed-in session inside Dereel. |

Dereel must not claim complete ad removal.

## Known gaps shown to users (NFR-11)

See `gap_*` strings in `app/src/main/res/values/strings.xml`. In summary:

- Inline reels in the Instagram home feed are not blocked.
- YouTube Shorts opened through a `/watch?v=` link are not recognized as Shorts.
- Native shared-reel exceptions are not offered.
- Google may refuse sign-in inside the embedded browser (D-08).
- Browser text filters are English-only.
- Facebook: the regular Facebook app is not supported. In Facebook Lite, any video opened in the
  full-screen player is treated as a reel, and videos playing inside the feed are not blocked.
  Inside Dereel, a reel that plays in the feed without opening its own page is not blocked, and
  shortcut hiding depends on the page layout.
- Picture-in-picture: a short video that an app keeps playing in a floating window after the
  user leaves it is not detected, so its time is not counted or limited (seen with YouTube on the
  Pixel 9, 21 Nov 2026). It can only start while short videos are allowed.
- Snapchat: only the Spotlight feed is recognised. Stories and Discover videos are not blocked,
  and Snapchat cannot be opened inside Dereel.
