# Requirement-to-test checklist (SRS v0.1)

Legend:
- **✅ Test**: automated test in this repo
- **🔬 Emulator**: observed on the Android 17 (API 37) emulator, 19 Nov 2026
- **🟡 Impl**: implemented; needs device verification or manual review
- **⏳ Pending**: not yet measured or not verifiable without the declared baseline (D-02)
- **📱 Phone**: observed on the owner's Pixel 9 in an informal session (see `docs/COMPATIBILITY.md`)
- **❌ Gap**: known not to work, and listed as a gap for users

Emulator observations are smoke evidence only. They are not compatibility verification (FR-38).

Test locations: `core/src/test/...` (JVM), `app/src/test/...` (Robolectric/JVM),
`tools/rules-tests/rules.test.mjs` (Node). Run everything with:

```
gradlew :core:test :app:testDebugUnitTest :app:lintDebug
node --test tools/rules-tests/rules.test.mjs
```

## Platforms added after SRS v0.1 (scope change S-01)

Facebook (Facebook Lite app and inside Dereel) and Snapchat (app only) reuse every requirement below through the
same policy, coordinator and usage code. The rows here cover what is specific to them. Evidence
is unit tests (✅), one emulator smoke run for Facebook and for the new screens (🔬, 20 Nov 2026),
and one informal phone session each for Snapchat and Facebook Lite (📱, 20 Nov 2026). Facebook
inside Dereel has not been used signed in, the regular Facebook app is not supported, and nothing
here is a recorded verification run (⏳).

Emulator run: the build installed over the previous one and kept its settings; Facebook and
Snapchat appeared as addable platforms; Facebook showed one status row (Inside Dereel) even with
Accessibility on; Snapchat, not installed there, showed Unavailable with no browser offer and an
OK-only "app not found" dialog; the Facebook navigation guard results are in
`docs/COMPATIBILITY.md`.

| Requirement | Status | Evidence |
|---|---|---|
| UX-04, UX-05: only supported methods are offered or shown | ✅ ⏳ | `CapabilitiesTest` (methods per platform match the adapters that exist; unsupported method has no capabilities; stored unsupported method repaired), `NativeScopeTest.storedMethodThatIsNotOfferedIsRepairedOnRead` |
| UX-08: no protection shown where none exists | ✅ 🔬 | `StatusEvaluatorTest` (Facebook gets an app row only when Facebook Lite is installed; Snapchat never offers the browser as recovery), `CapabilitiesTest` (the regular Facebook app is not a protected app) |
| FR-06, SEC-03: documented package scope | ✅ | `NativeScopeTest` (service `packageNames` equals the rule sets; every protected app is in `<queries>`; Facebook Lite is observed, the regular Facebook app is not) |
| FR-06, FR-07: Facebook Lite's Reels player recognised, feed untouched | ✅ 📱 | `NativeDetectionTest` Facebook Lite fixtures (full-screen player matched, smaller in-feed video and the feed not matched). Phone: player matched once, one Back after 78 ms, then gone. |
| FR-06, FR-07: Spotlight recognised, tabs left uncovered, chats untouched | ✅ 📱 | `NativeDetectionTest` Snapchat fixtures (lookup and traversal agree). Phone: Spotlight matched five times, other tabs never, a tab tap passed under the cover. |
| FR-10: one Back per episode, cover instead of a Back loop | ✅ 📱 | `NativeDetectionTest` episode tests. Phone: three entries gave one Back each, the fourth within 30 s gave a cover, and leaving Spotlight removed it. |
| NFR-05: response time | 📱 ⏳ | Phone, debug build: Back 68 to 100 ms and cover 81 ms after the triggering event on Snapchat. Not a reference-device measurement (D-02). |
| FR-12 to FR-15, FR-17: Facebook routes | ✅ 🔬 ⏳ (signed in) | `WebUrlClassifierTest` (routes, sign-in pages, link redirects), `RouteFixturesTest` + `rules.test.mjs` (Kotlin and page rules agree, including query strings), `BrowserNavigationControllerTest` (reel and Video tab blocked, named video untouched, in-page correction) |
| FR-18, FR-19: Facebook sign-in data | ✅ ⏳ | `PlatformWebTest` (scopes; clearing Instagram also ends the Facebook sign-in and is named; Snapchat has no browser data) |
| FR-20: no shared-video exception outside Instagram | ✅ | `CapabilitiesTest`, `WebUrlClassifierTest` (Facebook messages are never a conversation context), `BrowserNavigationControllerTest` |
| INT-01, INT-02: shared links and external links | ✅ ⏳ | `WebUrlClassifierTest` (Facebook links accepted by journey, Snapchat links refused, Facebook reels not reachable through Instagram's sign-in hosts) |
| FR-39: versioned bundled rules | ✅ | `BrowserRulesAssetTest` (version match, no network or storage use, Snapchat has no page rules), `NativeDetectionTest` (every rule set has a unique version) |

## Additions after SRS v0.1 (scope change S-02)

Evidence here is unit tests (✅), two emulator runs (🔬, 20 and 21 Nov 2026) and one informal
session on the owner's Pixel 9 with the YouTube app (📱, 21 Nov 2026). See
`docs/COMPATIBILITY.md`. None of it is a recorded verification run.

| Addition | Status | Evidence |
|---|---|---|
| Policy order with the sleep schedule and interval timer (extends POL-01, POL-03, POL-04) | ✅ 📱 | `TimeRulePolicyTest` (schedule beats allowance; temporary access beats both; a break and the schedule deny shared items; exhausted allowance beats the timer; the sooner limit wins). Phone: the schedule denied Shorts while an interval window was available, and its reason replaced the break's. |
| Interval timer windows, breaks and persistence | ✅ 📱 | `TimeRulesTest` (window then break then fresh window; time adds up only while viewing; a long pause resets; a clock change cannot stretch a break; progress survives a restart). Phone: a 5-minute window, then a 15-minute break that survived an app update. |
| Re-evaluation without a new adapter event (extends FR-27, POL-06) | ✅ 📱 | `TimeRulePolicyTest` (a window running out starts the break and the break ends by itself; the schedule starts and ends during viewing; turning the schedule on applies at once; app and browser share one window). Phone: the break started by itself at 5 min 0 s, Shorts were allowed again after it, and Dereel's browser showed the same break. |
| Sleep schedule times, midnight crossing and time zones | ✅ | `TimeRulesTest` |
| "Check protection" reads real state (UX-08, FR-36) | ✅ 📱 | `ProtectionCheckTest` (a switch that is on fails when the service does not answer; stale service settings fail; unseen apps are "not checked yet", not a pass). Phone: listed live results, with unopened apps as "not checked yet". |
| "Protection has stopped" alert rule | ✅ 🔬 | `ProtectionCheckTest` (fires only if asked for, set up once, and a selected app relies on it). Emulator: posted about 8 s after access was turned off, withdrawn when it returned, and a tap opens Home. Not tried on a phone. |
| Upgrade keeps settings, history and grants (DATA-01, NFR-10) | ✅ 📱 | `DatabaseMigrationTest` (a schema 1 database built from the exported schema is opened by the current app; Room validates the result), `SchemaGuardTest`. Phone: platforms, methods and settings were unchanged after the schema 1 to 2 update. |
| Block messages for a break and the schedule (UX-10) | 🔬 📱 | `BlockMessages`: states the reason and the end time. Phone: both messages seen in Dereel's browser. Emulator: the schedule banner seen over the YouTube app. The break banner over an app has not been looked at. |
| Live timer | 🔬 📱 | `TimerText` and `OverlayController.showTimer`. Emulator: seen counting down over YouTube Shorts and under the title in Dereel's browser. Phone: Dereel's timer window was present while Shorts were allowed and gone while denied; its text was not looked at. |
| Redesigned screens (UX-01 to UX-05, NFR-02, NFR-03) | 🔬 📱 | Screenshots of Home, "Check protection", Activity, Settings, a platform screen and the interval dialog on the phone, and of first-run setup on the emulator. TalkBack and large-text passes not repeated since the redesign. |
| Home reports app protection that has stopped, also for a platform set to open inside Dereel (UX-08, A-38) | ✅ 🔬 | `StatusEvaluatorTest` (protection that worked and was turned off is shown as Needs attention with a fix; never-enabled access is not nagged about). Emulator: seen before and after the change. |
| Short videos in picture-in-picture | ❌ gap | Not detected or counted (seen on the phone). Listed in Help and in `docs/COMPATIBILITY.md`; open decision D-14. |

## Policy (4.1)

| ID | Status | Evidence |
|---|---|---|
| POL-01 | ✅ | `PolicyEngineTest` (order, unavailable ≠ blocked), `ProtectionCoordinatorTest` (unavailable method, STARTING) |
| POL-02 | ✅ | `PolicyEngineTest` default blocks; shared allowed only for recognized item |
| POL-03 | ✅ | `PolicyEngineTest` shared consumes allowance, exhausted denies shared; `BrowserNavigationControllerTest` |
| POL-04 | ✅ | `ProtectionCoordinatorTest` grant bypasses exhausted allowance, usage continues, expiry resumes policy |
| POL-05 | ✅ | `PolicyEngineTest`, `ProtectionCoordinatorTest` regular content never counted |
| POL-06 | ✅ 🔬 | `ProtectionCoordinatorTest` settings change re-evaluates; app observes Room flow. Device timing ⏳ |
| POL-07 | ✅ | `PolicyEngineTest` blocking off allows and measures; `DatabasePersistenceTest` disabled allowance keeps limit |

## UX (5)

| ID | Status | Evidence |
|---|---|---|
| UX-01 | 🔬 | Home / Activity / Settings navigation; platform controls from Home |
| UX-02 | 🟡 | Copy rules in `strings.xml`; no internal terms in UI (review) |
| UX-03 | 🔬 | One Open action per platform; method saved (`SystemIntents`, `PlatformOpener`) |
| UX-04 | 🔬 | "Use the YouTube app" / "Open inside Dereel" with explanations |
| UX-05 | 🔬 🟡 | Shared-video control shown only for Instagram; More controls (browser only) |
| UX-06 | 🔬 | Three-step onboarding completed with defaults |
| UX-07 | ✅ 🔬 | Disclosure at the point of choosing the app method; "Not now" keeps other features (`StatusEvaluatorTest` AT-02) |
| UX-08 | ✅ 🔬 | `StatusEvaluatorTest` overall message rules; emulator showed "needs attention", then "active" only when all methods worked |
| UX-09 | ✅ 🔬 | Reason and recovery per status; refresh on resume and on service callbacks |
| UX-10 | 🔬 | Specific block messages with exits; temporary access only when enabled |
| UX-11 | 🟡 | No streaks or time-saved figures (review) |

## Functional (6)

| ID | Status | Evidence |
|---|---|---|
| FR-01 | 🔬 | No account and no sign-in during onboarding |
| FR-02 | ✅ | `StatusEvaluatorTest` method not chosen → Needs setup; Open asks once |
| FR-03 | ✅ 🔬 | Missing Instagram app: native option disabled with explanation; `StatusEvaluatorTest` Unavailable + browser recovery |
| FR-04 | ✅ 🔬 | `AccessibilityEnabledTest`; status follows the real binding state (emulator) |
| FR-05 | ✅ | `ProtectionCoordinatorTest` removal stops enforcement and measurement; history kept |
| FR-06 | ✅ 🔬 | `NativeDetectionTest` fixtures; **YouTube 20.10.41 Shorts player matched by `reel_recycler`** (emulator). Instagram ⏳ (app not installed) |
| FR-07 | ✅ 🔬 | Cover excludes the navigation region; emulator cover left YouTube's bottom navigation usable |
| FR-08 | ✅ 🟡 | Leave on app switch, screen off and unbind; presence check re-runs detection so stale covers clear. Fix not yet re-verified on device |
| FR-09 | 🔬 🟡 | System permission dialog and ANR dialog in front: no action taken (emulator). Keyboard ⏳ |
| FR-10 | ✅ 🔬 | `NativeDetectionTest` one Back per episode, cooldown, confirmation, loop cap; emulator: Shorts tab → single Back → Home |
| FR-11 | ✅ | Native never grants shared exception (`PolicyEngineTest`); hint names the route through Dereel |
| FR-12 | ✅ 🔬 | HTTPS entry routes (`PlatformWebTest`); default user agent (D-08); auth hosts allowed (`WebUrlClassifierTest`) |
| FR-13 | ✅ 🔬 | Controller tests for in-page route, refresh, Back/Forward; emulator: `pushState('/shorts/…')` corrected with one Back |
| FR-14 | ✅ 🔬 | YouTube search and regular video page work inside Dereel (emulator); Instagram journeys ⏳ (needs sign-in) |
| FR-15 | ✅ 🔬 | Blocked before load (controller); shared route fixtures for Kotlin and JS; emulator: tap on `/shorts/` link stopped in page |
| FR-16 | ✅ 🔬 | JS scoping tests; emulator: YouTube Shorts tab hidden, home grid hidden, 106/106 Shorts links in search hidden, up-next hidden. Instagram ⏳ |
| FR-17 | ✅ 🔬 | Safe route and loop guard (controller); emulator: shared Shorts link → "Go to YouTube home" |
| FR-18 | 🟡 | No credential form; sign-out action; one shared cookie jar |
| FR-19 | ✅ | `PlatformWebTest` scopes do not overlap; linked accounts disclosed in dialog |
| FR-20 – FR-23 | ✅ | `SharedItemExceptionTrackerTest`, `BrowserNavigationControllerTest`. Live Instagram DM journey ⏳ |
| FR-24 | ✅ | Allowance choices and validation (`PolicyEngineTest`) |
| FR-25 | ✅ | Time behind a block not counted (`ProtectionCoordinatorTest`) |
| FR-26 | ✅ | `UsageTrackerTest` duplicates and AT-08 6+4=10 |
| FR-27 | ✅ | Allowance runs out mid-viewing → Deny without a new event |
| FR-28 | ✅ | Midnight split, clock moved back, timezone change (`UsageTrackerTest`) |
| FR-29 | ✅ | Issue, replace, end early, expiry (core); replacement confirmation in UI |
| FR-30 | ✅ | `ProtectionCoordinatorTest` disable/re-enable same day |
| FR-31 | 🟡 | Activity screen (today + 6 days, native/browser split) |
| FR-32 | ✅ | Segment fields; thread IDs redacted from `toString` |
| FR-33 | ✅ | 5 s checkpoints; recovery through Room (`DatabasePersistenceTest`) |
| FR-34 | ✅ | Retention 30 days, clear history (`UsageTrackerTest`) |
| FR-35 | 🟡 | Separate Settings actions with scoped confirmations |
| FR-36 | ✅ 🔬 | Status changed immediately on unbind/rebind (emulator logs) |
| FR-37 | ✅ | Recovery statuses for revoked access, WebView, missing app, layout; nothing deleted |
| FR-38 | 🟡 | `COMPATIBILITY.md` and Help screen; nothing marked device-verified |
| FR-39 | ✅ | Versioned rules, route fixtures, asset audit, no remote rules |

## Data, integration, security

| ID | Status | Evidence |
|---|---|---|
| DATA-01 | ✅ | Room schema exported; `SchemaGuardTest`; restart persistence. First migration test arrives with schema v2 |
| DATA-02 | ✅ | One coordinator and tracker for both methods |
| DATA-03 | ✅ | Monotonic accrual; recovery adds no unobserved time |
| DATA-04 | 🟡 | Offline/page errors stop measurement and show an error state |
| INT-01 | ✅ 🔬 | `SharedLinkParser` tests; emulator: Shorts link shared to Dereel validated and blocked |
| INT-02 | ✅ | External links need confirmation; `intent:` and app schemes refused |
| INT-03 | ✅ | Close/background ends the session (controller tests) |
| SEC-01 | ✅ | No analytics/ads dependencies; rules asset audit (no network APIs) |
| SEC-02 | 🔬 | Disclosure dialog reviewed on emulator |
| SEC-03 | ✅ | `UiNode` exposes no text; debug-only diagnostics carry anchor names and timings only |
| SEC-04 | ✅ 🔬 | `BridgeProtocolTest`; bridge object present only on the platform origin (emulator) |
| SEC-05 | 🟡 | TLS errors cancelled, file/mixed content off (code review) |
| SEC-06 | 🟡 | No cookie or URL logging; no cookie-slot switching |
| SEC-07 | 🟡 | `allowBackup=false` + extraction rules; reset clears data |
| SEC-08 | ✅ | Independent implementation; SocialLite material excluded by `.gitignore`; Scrolless (GPL-3.0) not used |

## Nonfunctional

| ID | Status | Notes |
|---|---|---|
| NFR-01 | ⏳ | Moderated user test |
| NFR-02, NFR-03 | 🟡 ⏳ | 48 dp targets, roles and labels in components; formal TalkBack and 200 % font inspection pending |
| NFR-04 | ⏳ | Emulator cold start 5.9 s (debug build, software GPU): not representative |
| NFR-05 | ⏳ | Emulator: detection → Back 103 ms after switching to in-process ID lookup; event → action not measurable on the saturated emulator |
| NFR-06 | ⏳ | CSS rules apply on insertion; instrumented measurement pending |
| NFR-07 | ✅ ⏳ | Exact in unit tests; device reference-timer run pending |
| NFR-08 | ✅ ⏳ | Back-loop and redirect-loop guards tested; 60-minute run pending |
| NFR-09 | ✅ 🔬 | No checkpoint loop when idle (unit); emulator screen-off: 0.03 % of one core, no wake lock held |
| NFR-10 | ✅ 🔬 | `DatabasePersistenceTest`; settings survived app update on emulator |
| NFR-11 | 🟡 | Help screen lists rule versions and known gaps |

## Acceptance scenarios

AT-01, AT-02, AT-03 (YouTube only), AT-05 (partial), AT-13 and AT-16 have emulator or test
evidence. AT-04, AT-06, AT-14, AT-15 and AT-17 to AT-24 need the declared device baseline (D-02),
signed-in platform accounts, or manual review.

## Emulator findings that changed the code

1. **Stale Shorts views after Back** were still reported visible, so the episode escalated to a
   false cover over YouTube's Home tab. Fixed with a confirmation check before covering
   (`DenyEpisodeController`, `confirmMs`).
2. **A cover persisted when the app changed screens without sending more events** (YouTube's
   consent modal). Fixed: the presence check re-runs detection while a cover or session is active.
3. **Detection cost**: a client-side tree walk made one IPC per node on the target's UI thread
   (about 1.7 s on the emulator). Replaced with in-process view-ID lookup (103 ms), throttled
   content-change detection to 500 ms, and dropped scroll events.
4. **Open risk**: YouTube showed ANR dialogs on the saturated emulator (load average 15–30). One
   control run without Dereel bound produced no ANR in 75 s. This is not conclusive, but Dereel's
   impact on the target app's responsiveness must be measured on the reference device before
   release.
