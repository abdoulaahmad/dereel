# Dereel Software Requirements Specification

**Version:** 0.1  
**Status:** Draft for product and engineering review  
**Date:** 19 November 2026  
**Product:** Dereel for Android

## 1 Purpose

Dereel helps people use social media intentionally by reducing access to endless short-video feeds while preserving useful activities such as messaging, search and viewing deliberately selected content.

The product combines protection inside supported native social apps with an optional filtered browser inside Dereel. Both experiences use the same platform settings and daily usage records. Users should understand what is blocked, what remains available and whether protection is working.

This specification defines a proposed first release, its acceptance criteria and later capabilities. Proposed defaults and scope decisions are recorded explicitly; this document does not represent approval of those decisions. It is intended for product, design, engineering, testing and open-source contributors.

### 1.1 Requirement language

- **Shall** identifies a mandatory requirement within the proposed first-release scope.
- **Should** identifies a recommendation that may be changed with a recorded reason.
- **Later scope** identifies functionality excluded from first-release acceptance.
- Requirement identifiers remain stable when wording changes. Removed requirements shall be marked retired rather than reused.

### 1.2 Product goals

1. Reduce unintentional movement into Reels and Shorts feeds.
2. Preserve messaging, search, profiles and regular YouTube videos where supported.
3. Provide an understandable setup with a useful result after configuring one platform.
4. Apply consistent user rules across supported native and browser experiences.
5. Keep first-release settings and usage data on the device.

Dereel is a voluntary self-control tool. It does not guarantee protection against uninstalling, permission removal, modified devices or every change made by third-party apps.

## 2 Product scope

### 2.1 Proposed first release

The first release shall support Instagram and YouTube on Android and include:

- Native Reels and Shorts detection with user-enabled accessibility access.
- A filtered browser for supported Instagram and YouTube journeys.
- Platform settings shared across both opening methods.
- Blocking by default, with an optional daily short-video allowance.
- A single shared-video exception where origin and item identity can be recognized reliably.
- Home, Activity and Settings screens.
- Local usage history, protection status, permission recovery and voluntary temporary access.
- Clear capability differences between native and browser experiences.

### 2.2 Later scope

The following are excluded from first-release acceptance: other social platforms; device-wide DNS or VPN filtering; parental accounts and remote management; cloud synchronization; AI classification of posts; multiple saved accounts per platform; subscriptions and paywalls; background audio; Picture-in-Picture; whole-app blocking; interval schedules; and uninstall protection.

These capabilities may be added in later versions without changing the first-release requirement identifiers. They shall not appear as working controls before implementation and verification.

### 2.3 Platform capability boundary

| Capability | Native app protection | Inside Dereel |
|---|---|---|
| Detect and block Reels or Shorts | Required on documented supported screens | Required on documented supported routes and page states |
| Preserve messaging and search | Required in supported journeys | Required in supported journeys |
| Allow one shared video | Available only after adapter-specific verification | Required for the supported Instagram shared-reel journey |
| Apply one daily platform allowance | Required | Required |
| Hide recommendations and Explore recommendations | No first-release guarantee | Required for documented supported Instagram and YouTube surfaces |
| Filter identifiable social-site ads | No first-release guarantee | Best-effort filtering with documented tested coverage |
| Keep multiple saved logins | Later scope | Later scope |

Here, required coverage means the explicit compatibility matrix and release test cases, not every possible third-party UI variant. Best-effort ad filtering shall not be marketed as complete ad removal.

## 3 Users and operating context

### 3.1 Primary user

An Android user who wants to retain useful social features while reducing short-video consumption. The user may prefer the original social app, the filtered Dereel browser, or a different choice for each platform.

### 3.2 Maintainer

A contributor who maintains platform detectors, browser rules, compatibility records, tests and releases. This is a development role, not an additional consumer account or dashboard.

### 3.3 External systems

- Android accessibility services and system settings.
- Android System WebView and supported authentication providers.
- Installed Instagram and YouTube applications.
- Instagram and YouTube web applications and their network services.

Native protection requires supported apps to be installed and accessibility access to be enabled. Browser access requires network connectivity for social-site content, but local settings and history shall remain accessible offline.

### 3.4 Proposed implementation constraints

The proposed implementation is a native Kotlin and Jetpack Compose application, using Android WebView for browser journeys. Native detection and browser integration shall remain separate from shared policy and usage logic. Android version support, baseline hardware and source-license selection remain decisions in Section 12.

## 4 Terms and policy semantics

| Term | Meaning |
|---|---|
| Platform | Instagram or YouTube in the first release |
| Opening method | The installed social app or the browser inside Dereel |
| Short-video surface | A supported Reels or Shorts screen, viewer or feed identified by an adapter |
| Shared-video exception | Permission to view one recognized item deliberately opened from a supported message or shared-link journey |
| Daily allowance | A user-selected amount of short-video viewing time for one platform, shared across opening methods |
| Adapter | Platform-specific detection or enforcement logic for a native app or browser |
| Policy engine | Shared logic that decides whether a recognized activity is permitted |
| Protection status | The verified operational state of an enforcement method for a platform |
| Viewing session | A continuous period when a recognized short-video surface is visible in the foreground |

### 4.1 Rule order

**POL-01** The policy engine shall first check whether the platform is selected and short-video blocking is enabled. For an enabled platform, it shall apply rules in this order: unavailable capability or enforcement method; active user-authorized temporary access; exhausted enabled daily allowance; recognized shared-video exception; remaining enabled daily allowance; and default blocking.

An unavailable method shall be reported as unavailable, rather than treated as successful blocking. An adapter shall continue to block positively identified prohibited content when it can do so safely. If the activity cannot be identified reliably, it shall not interfere with unrelated screens and shall report the relevant limitation.

**POL-02** With no daily allowance enabled, the default policy shall block short-video feeds. Enabling the shared-video exception shall permit only the recognized shared item under that policy.

**POL-03** With a daily allowance enabled, all recognized short-video viewing, including shared items, shall consume that allowance. Exhausting it shall block further short-video viewing until reset or an explicit temporary-access grant.

**POL-04** A temporary-access grant shall bypass short-video blocking for its stated duration and platform. Usage shall continue to be recorded and shall not reduce or reset accrued usage.

**POL-05** Messaging, profiles, search and regular YouTube videos shall not consume the short-video allowance unless the user enters a recognized short-video surface.

**POL-06** Settings changes shall take effect in an active supported journey within two seconds of a successful save, without requiring an app restart. Unsupported live changes shall be identified and applied before the next launch of that journey.

**POL-07** Disabling a platform's short-video blocking shall remove its content restrictions while retaining usage measurement for that selected platform. The interface shall show protection as off, shall retain saved allowance settings for reactivation and shall not delete history. Removing the platform from Dereel shall stop both enforcement and measurement as specified by FR-05.

## 5 User experience requirements

### 5.1 Navigation and language

**UX-01** Dereel shall provide three primary destinations: Home, Activity and Settings. Platform controls shall be reachable from the corresponding platform entry on Home.

**UX-02** Consumer screens shall describe outcomes using terms such as Block Reels, Allow shared videos and Daily allowance. Internal terms such as adapter, DOM injection and policy engine shall not appear in normal setup or controls.

**UX-03** Each platform shall have one primary Open action. Dereel shall remember its opening method and shall not ask the user to choose again on every launch.

**UX-04** The opening-method choices shall be labelled Use the Instagram app or Use the YouTube app, and Open inside Dereel. Each choice shall include a short explanation of the available protection.

**UX-05** Basic platform settings shall expose short-video blocking, the supported shared-video exception and an optional daily allowance. Additional browser controls shall appear under More controls. Unavailable controls shall explain the limitation or be omitted from that method.

### 5.2 Setup and status

**UX-06** Initial setup shall contain three stages: select platforms, choose initial rules and activate required protection. Users shall be able to complete setup with one platform and add another later.

**UX-07** Dereel shall explain requested access at the point a user enables the dependent feature, offer a cancellation path and retain access to independent features when access is declined.

**UX-08** Home shall display a status for each selected platform and opening method: Active, Off, Paused, Needs setup, Needs attention or Unavailable. An overall active message shall appear only when every enabled method it represents is operational.

**UX-09** A status requiring user action shall include a reason and one primary recovery action. Returning from Android settings shall trigger a fresh status check.

**UX-10** Blocking messages shall state the specific reason and provide a relevant exit such as Go back or Return to messages. Temporary access shall be secondary and visible only when enabled by the user's preferences.

**UX-11** The interface shall avoid guilt-based language, fabricated time-saved claims, mandatory streaks and repeated prompts to enable optional features.

## 6 Functional requirements

### 6.1 Setup and platform management

**FR-01** The system shall allow platform selection without creating a Dereel account or signing into a social platform during onboarding.

**FR-02** The system shall save rules and the opening method separately for each platform. If an opening method is not yet selected, it shall request that choice once before the first Open action completes.

**FR-03** When a selected native app is absent, Dereel shall explain that native opening is unavailable and offer the supported browser alternative. It shall not silently install an app or change the saved opening method.

**FR-04** The system shall guide users to the relevant Android accessibility setting for native protection. It shall verify actual service availability after the user returns, rather than assume that opening settings granted access.

**FR-05** Removing a platform from Home shall stop its enforcement and session tracking. Dereel shall retain its history until the user explicitly deletes it and shall explain this behaviour at removal.

### 6.2 Native app protection

**FR-06** Native adapters shall identify supported Reels and Shorts surfaces from accessibility events and visible UI state within the documented package scope. They shall not block an entire app solely because its package is in the supported list.

**FR-07** When policy denies a positively recognized short-video surface, the native adapter shall leave that surface with a bounded Back action or cover the supported content region. The chosen action shall preserve unrelated messaging and navigation controls where the supported layout permits.

**FR-08** Native enforcement shall stop acting when a supported app is no longer visible, the device is locked, the user disables protection or access is revoked. Overlays shall be removed when their target is no longer present.

**FR-09** Native detection shall distinguish foreground app windows from keyboards, system UI and Dereel's own overlays. A transition involving these windows shall not create a false viewing session or repeated Back action.

**FR-10** Native adapters shall bound automated navigation to at most one Back action per detection episode, with a minimum one-second cooldown and a fresh state check before another episode. They shall not create an indefinite Back-navigation loop.

**FR-11** A native shared-video exception shall be exposed only for tested app versions and journeys where both origin and item identity are sufficiently distinguishable. If that recognition fails, the adapter shall apply the ordinary short-video rule and explain an available deliberate route through Dereel where supported.

### 6.3 Browser experience

**FR-12** The browser shall open the selected platform using a documented HTTPS entry route and user-agent strategy. Authentication redirects shall remain available only through explicitly supported flows and origins.

**FR-13** Browser adapters shall apply the platform's shared policy during initial navigation, full-page navigation, in-page route changes and dynamically loaded content. Refreshing or using Back and Forward shall not silently disable the configured policy.

**FR-14** The Instagram browser shall support tested messaging, profile, search and shared-reel journeys. The YouTube browser shall support tested search, channel and regular-video journeys.

**FR-15** Browser controls shall hide or block documented Reels and Shorts entry points and feeds when denied by policy. Native navigation checks and page-level enforcement shall cooperate without repeatedly reloading an allowed page.

**FR-16** The browser shall provide tested controls for Instagram Explore recommendations and suggested feed items, and YouTube home and watch recommendations. Identifiable ad filtering shall have an explicit tested-coverage statement and shall not claim to remove every ad.

**FR-17** A denied browser route shall return the user to the last valid context when available; otherwise it shall offer a documented safe platform route. Failed redirects shall terminate in an understandable Dereel error state rather than loop.

**FR-18** Social sign-in shall occur through the platform's supported browser flow. Dereel shall not implement a credential-collection form or store social passwords itself. The first release shall support one active browser login per platform and a documented sign-out action.

**FR-19** Sign-out and platform data deletion shall state which sessions and origin data will be cleared. The implementation shall not silently clear another platform's login; dependencies between linked authentication origins shall be disclosed before confirmation.

### 6.4 Single shared video

**FR-20** For the supported Instagram browser journey, Dereel shall associate a shared-item exception with its originating conversation or link and the first recognized item. It shall not grant a general feed-wide exemption.

**FR-21** The user shall be able to finish, pause, resume and replay that same item while remaining in the supported context, subject to any enabled allowance. Advancing to a different short video shall end the exception and return to the originating context or a safe fallback.

**FR-22** A quality change, DOM re-render or additional suggestions loading shall not by itself be treated as a new item in the release test fixtures. Item detection shall prefer stable identity and corroborated state over a changing media URL alone.

**FR-23** An exception shall end on leaving its context, closing the viewer, changing accounts or starting an unrelated journey. Exception state shall not survive process restart as an unrestricted allowance.

### 6.5 Allowances and temporary access

**FR-24** The system shall support one optional daily allowance per platform, shared across native and browser viewing. Suggested choices shall be 5, 15 and 30 minutes, with a custom integer range of 1 to 180 minutes. These choices are proposed defaults, subject to Decision D-04.

**FR-25** Usage shall accrue only while a recognized short-video surface is visible in the foreground. Visible time shall count even when a video is paused. Time behind a blocking cover, on the lock screen or in an unrelated app shall not accrue as viewing time.

**FR-26** One elapsed second shall contribute at most one second to a platform allowance. Overlapping adapter events, duplicate callbacks and transitions between opening methods shall not double-count usage.

**FR-27** When an allowance reaches zero, the active adapter shall apply the denied-content action and the interface shall show the exhausted state. Dereel shall not close unrelated app screens or report that access is blocked if enforcement is unavailable.

**FR-28** A daily allowance shall reset at the next device-local midnight. Sessions crossing midnight shall be split between days. Date and timezone changes shall preserve accrued records, shall not create negative durations and shall not reduce usage already attributed to an existing day. This is not a tamper-proof clock policy.

**FR-29** If temporary access is enabled, the user shall be able to authorize five minutes for a named platform. The interface shall show its expiry, allow early termination and resume normal policy after expiry. A second request shall explicitly state that it replaces the existing expiry with a new five-minute period.

**FR-30** Disabling a daily allowance shall not delete recorded usage. Re-enabling it on the same day shall apply the current limit against that day's accrued usage.

### 6.6 Activity and local data

**FR-31** Activity shall show current-day usage and the previous six days for each selected platform, separating native and browser contributions while displaying a combined platform total.

**FR-32** Usage records shall include platform, opening method, start and end timestamps, elapsed viewing time, date bucket and enforcement outcome where applicable. Production usage records shall exclude messages, page text, screenshots, full browsing URLs and social account identifiers.

**FR-33** Completed sessions shall be stored at session end. In-progress elapsed usage shall be checkpointed at least every five seconds so that an unexpected process death loses no more than five seconds of already measured time.

**FR-34** Usage history shall retain a rolling 30 days locally and remove older session records automatically. Clearing history shall remove those records and derived totals after confirmation; it shall disclose that the current day's allowance will consequently restart from zero.

**FR-35** Settings shall provide separate actions to clear usage history, clear a platform's browser data and reset Dereel. Destructive confirmations shall state the affected data and whether social sessions will end.

### 6.7 Reliability and compatibility

**FR-36** Dereel shall re-evaluate operational status on foreground entry, return from system settings, relevant service lifecycle events and platform launch. A native service disconnection shall change status within five seconds of an observable disconnection callback, or on the next foreground check if no callback is delivered.

**FR-37** The system shall provide a recovery flow for revoked accessibility access, unavailable WebView, missing social apps, offline content and unsupported platform layouts. Recovery shall preserve saved rules and history.

**FR-38** The release shall include a compatibility matrix identifying tested Android versions, native app versions, WebView versions, languages, account/layout variants and supported journeys. Unsupported features shall not be represented as verified.

**FR-39** Platform rules and compatibility changes shall be versioned and covered by regression fixtures. The first release shall ship executable browser rules with the application and shall not download arbitrary executable rules from a remote configuration service.

## 7 Data and integration requirements

### 7.1 Logical records

| Record | Required fields |
|---|---|
| Platform configuration | Platform ID, enabled state, opening method, blocking rule, shared-item setting, daily allowance, supported browser controls, revision |
| Viewing session | Local session ID, platform, method, timestamps, monotonic elapsed time, day bucket, outcome |
| Temporary grant | Platform, issue time, expiry, active or ended state |
| Method health | Platform, method, operational state, last check, reason code, recovery action |
| Compatibility profile | Adapter/rule version, supported environment, tested capabilities, known limitations |

**DATA-01** Settings, grants, usage and health records shall use app-private local storage. Schema changes shall preserve valid existing settings and usage through a tested migration.

**DATA-02** Both adapter types shall call the same policy and usage interfaces. Neither shall maintain an independent allowance balance.

**DATA-03** Time measurement shall use a monotonic clock for elapsed durations and wall-clock timestamps only for display and day attribution. Process recovery shall not invent viewing time during the interval when Dereel could not observe the screen.

**DATA-04** Offline mode shall support configuration, history, native protection where available and clear method status. Browser network errors shall not be interpreted as zero usage or successful content filtering.

### 7.2 External navigation

**INT-01** Platform launches shall use the saved opening method. Opening a shared URL through Dereel shall validate the scheme, host and supported path before selecting the platform and applying policy.

**INT-02** External non-platform links shall require an explicit user action before opening another app. Unknown custom schemes and untrusted intent fallback URLs shall not execute automatically.

**INT-03** The browser shall expose normal Back, refresh and an accessible route back to Dereel. Closing a browser screen shall finish or checkpoint the active viewing session.

## 8 Nonfunctional requirements

The following are proposed acceptance targets. Device and network baselines shall be fixed under Decisions D-01 and D-02 before results are measured.

| ID | Requirement | Verification |
|---|---|---|
| NFR-01 | At least four of five first-time test users shall configure one platform and correctly identify its protection status without assistance within three minutes. | Moderated task test including permission navigation; report failures and reasons |
| NFR-02 | Primary controls shall have at least 48 dp touch targets, meaningful accessible names and a logical TalkBack focus order. | Accessibility inspection on each primary flow |
| NFR-03 | Essential text and actions shall remain usable at 200 percent system font scaling; normal text contrast shall be at least 4.5 to 1 and large text at least 3 to 1. | Font-scale and contrast checks; no colour-only status meaning |
| NFR-04 | Home shall become interactive within two seconds at the 95th percentile of 30 cold launches on the baseline device, without waiting for social-site network requests. | Instrumented launch measurements |
| NFR-05 | A native denied-surface action shall begin within 750 ms at the 95th percentile after Dereel receives a relevant event with a recognizable stable supported screen. | At least 30 transitions per required native journey; event-to-action measurement |
| NFR-06 | A tested browser recommendation or blocked-entry element shall be hidden or made non-interactive within 500 ms of becoming observable by an installed rule. | Instrumented DOM fixtures; record any initial exposure separately |
| NFR-07 | Session duration shall differ by no more than two seconds from a reference timer during a five-minute uninterrupted foreground test. | Repeat for both methods and boundary transitions |
| NFR-08 | No release-blocking crash, stuck blocking overlay, repeated Back loop or unrecoverable browser redirect loop shall occur during a 60-minute mixed-journey regression run. | Device regression suite |
| NFR-09 | With the screen off and no pending user grant or session, Dereel shall not run continuous content-scanning loops or hold an application wake lock. | Lifecycle, scheduler and wake-lock inspection |
| NFR-10 | Settings and completed usage shall survive normal restart, supported schema migration and service reconnection. | Persistence and migration tests |
| NFR-11 | Each release shall identify its supported environments and known enforcement gaps in user-accessible help. | Release checklist against the compatibility matrix |

No network page-load time is guaranteed by NFR-04. NFR-05 and NFR-06 measure the app's response after actionable evidence is available; they do not guarantee that third-party content can never appear before detection.

## 9 Privacy and security requirements

**SEC-01** The first release shall require no Dereel account, analytics SDK, advertising SDK or automatic usage upload. Social browsing shall still make the network requests required by the selected third-party service.

**SEC-02** Before accessibility activation, Dereel shall explain that the service can inspect visible app content to recognize supported screens, what it uses, and its local processing and retention behaviour. It shall not claim that accessibility access is technically incapable of exposing sensitive screen content.

**SEC-03** Production accessibility processing shall be limited to supported detection and enforcement needs. Dereel shall not persist or upload message contents, screenshots or full accessibility trees. Diagnostic capture of sensitive UI shall be disabled in release builds.

**SEC-04** Web-to-native communication shall validate origin, frame context and message structure, expose a minimal command set and reject messages from untrusted origins. Untrusted page input shall not trigger filesystem access, shell execution or arbitrary Android intents.

**SEC-05** Browser integration shall not bypass TLS certificate errors. File access, mixed-content access and unnecessary cross-origin privileges shall remain disabled unless a specific documented feature requires and tests a narrowly scoped exception.

**SEC-06** Authentication tokens and cookies shall remain in the platform's app-private session mechanisms. They shall not appear in logs, usage exports or crash diagnostics. The first release shall not implement cookie-slot switching as a claim of isolated browser profiles.

**SEC-07** Persisted app data shall be excluded from automatic cloud backup in the first release. Reset shall remove Dereel-owned settings, grants, history and browser session data, and shall explain any third-party data outside its control.

**SEC-08** The implementation shall use independently authored code and assets for SocialLite-inspired behaviour. If Scrolless code is incorporated, its applicable license, notices and modification history shall be handled before distribution under Decision D-03.

## 10 Acceptance scenarios

Acceptance is assessed against the release compatibility matrix. A passing fixture is evidence for its environment, not universal compatibility with all social-app versions.

| Test | Scenario and expected result | Requirements |
|---|---|---|
| AT-01 | A new user selects Instagram, saves initial rules and activates a chosen opening method. No Dereel account is required; Home shows the verified status and one Open action. | UX-01 to UX-09; FR-01 to FR-04 |
| AT-02 | A user declines accessibility setup. Native protection shows Needs setup, while settings, history and the supported browser remain usable. | UX-07 to UX-09; FR-04; FR-37 |
| AT-03 | With blocking enabled, a supported native Instagram Reels or YouTube Shorts screen is detected and denied. DMs, search, profiles and regular videos remain usable in tested journeys. | POL-02; FR-06 to FR-10; NFR-05 |
| AT-04 | A keyboard, system dialog, lock-screen transition and app switch occur during native viewing. No false session, lingering overlay or Back loop results. | FR-08 to FR-10; FR-25; FR-26 |
| AT-05 | A browser user enters a blocked route through a direct link, an in-page route change, refresh, Back and Forward. Policy remains active; allowed authentication and messaging journeys still work. | FR-12 to FR-17 |
| AT-06 | A shared Instagram reel opens from a supported conversation. The same item can pause, resume and replay; advancing returns to the conversation. Quality changes and suggestions loading alone do not dismiss it. | POL-03; FR-20 to FR-23 |
| AT-07 | A shared-item origin or identity is unrecognizable. Dereel applies ordinary policy and does not grant an unrestricted feed exception. Unsupported native exceptions are not advertised. | POL-01; FR-11; FR-20; FR-38 |
| AT-08 | A user consumes six minutes of an allowance in the native app and four minutes inside Dereel. The platform total is ten minutes, without overlap or a second balance. | FR-24 to FR-27; DATA-02; DATA-03 |
| AT-09 | An allowance expires during viewing and while viewing a shared item. Further short-video access is denied with the correct reason; regular content remains available. | POL-03; UX-10; FR-27 |
| AT-10 | A five-minute temporary grant is issued, ended early and separately allowed to expire. Usage continues, the displayed expiry is correct and normal policy resumes. | POL-04; FR-29 |
| AT-11 | A session crosses midnight, the timezone changes, or the clock moves backwards. No negative duration or lost accrued record occurs; the documented day attribution is retained. | FR-28; DATA-03 |
| AT-12 | Dereel is terminated during a measured session and restarted. Saved settings survive, no exception becomes unrestricted, and measured-time loss is no more than five seconds. Unobserved time is not invented. | FR-23; FR-33; DATA-03; NFR-10 |
| AT-13 | Accessibility is revoked or the service disconnects. Status updates at the specified observation boundary and Fix protection leads to recovery without deleting settings or history. | UX-08; UX-09; FR-36; FR-37 |
| AT-14 | A user signs out or clears one platform's browser data. The confirmation names the affected sessions and no undisclosed other-platform login is cleared. | FR-18; FR-19; FR-35 |
| AT-15 | A user clears history or resets Dereel. Relevant records are removed; resetting history explicitly explains the allowance effect; reset ends Dereel-owned browser sessions. | FR-34; FR-35; SEC-07 |
| AT-16 | An untrusted frame or URL sends malformed bridge messages, custom schemes or intent fallbacks. No privileged operation or automatic external app launch occurs. TLS failures are not bypassed. | INT-01; INT-02; SEC-04; SEC-05 |
| AT-17 | TalkBack, large text, low contrast checks, cold launch and response measurements satisfy the proposed targets on the declared baseline. | NFR-01 to NFR-08 |
| AT-18 | Offline use, an unavailable WebView and an unsupported native layout each produce accurate status and an understandable recovery path while preserving local data. | FR-37; FR-38; DATA-04 |
| AT-19 | A user disables and re-enables blocking and the daily allowance during a session. Restrictions and status change within the specified boundary; accrued usage and saved settings are retained. | POL-06; POL-07; FR-30 |
| AT-20 | Opening methods and advanced controls show only supported capabilities. The chosen method is remembered, external links require deliberate action, and Back or closing the browser ends or checkpoints its session. | UX-02 to UX-05; INT-01 to INT-03 |
| AT-21 | A review of setup, blocking and Activity screens finds specific reasons, accurate usage, relevant exits, no invented time-saved values and no unsupported feature controls. | UX-10; UX-11; FR-16; FR-31 |
| AT-22 | Schema migration preserves settings and usage; retention removes records older than 30 days. Removing a platform stops enforcement and measurement while retaining history until explicit deletion. | FR-05; FR-31 to FR-35; DATA-01; DATA-02; NFR-10 |
| AT-23 | Release-build, network and log review finds no Dereel analytics or automatic usage upload, no sensitive UI capture or logged credentials, and truthful accessibility disclosure. Backup and reset behaviour match the specification. | SEC-01 to SEC-03; SEC-06; SEC-07; FR-32 |
| AT-24 | An idle locked-device run shows no continuous scan loop or app wake lock. Release review confirms rule versioning, regression coverage, compatibility help and applicable source/asset license handling. | NFR-09; NFR-11; FR-38; FR-39; SEC-08 |

### 10.1 Release completion criteria

A first release is ready when all applicable mandatory requirements have passing acceptance evidence on the declared environments, critical security findings are closed, compatibility gaps are documented and no control advertises an unverified capability. Best-effort filtering coverage shall be reported separately from required-flow pass results. Scope changes shall update the requirements and acceptance matrix before release sign-off.

## 11 Risks and limitations

| Risk | Required response |
|---|---|
| Native app layout, view ID or language changes break recognition | Versioned detectors, multilingual fixtures, compatibility records and accurate method status |
| Social websites change routes, markup or authentication | Separate platform adapters, browser regression journeys and supported-flow documentation |
| Android or a device vendor interrupts background operation | Reconnection checks, visible recovery and no false active claim |
| Native shared-item identity is ambiguous | Limit the capability to verified journeys and apply ordinary policy when uncertain |
| Web session isolation is overstated | One active login per platform initially; explicitly scoped clearing and tested authentication boundaries |
| Browser ad filtering is incomplete | Publish tested coverage and avoid claims of complete removal |
| Daily usage is affected by clock changes or process death | Monotonic elapsed accounting, checkpointing, preserved records and explicit self-control limitations |
| Accessibility and distribution requirements change | Verify applicable Android and distribution requirements before release and maintain truthful disclosures |

## 12 Open decisions

These decisions shall be resolved before the corresponding implementation or release baseline. They do not authorize later-scope features.

| ID | Decision | Proposed direction | Resolve before |
|---|---|---|---|
| D-01 | Minimum Android version, target SDK and device coverage | Select a baseline that supports the required native and WebView flows; validate current distribution requirements | Implementation baseline |
| D-02 | Reference devices and measurement conditions | At least one physical midrange device and one additional Android/vendor configuration; record app and WebView versions | Performance and compatibility acceptance |
| D-03 | Dereel source license and Scrolless reuse | Keep SocialLite-inspired implementation independent; decide permitted Scrolless reuse and license before incorporating code | Source integration |
| D-04 | Default shared-video setting and allowance choices | Blocking enabled; allowance disabled until chosen; shared-item exception enabled only on verified adapters | UX baseline |
| D-05 | Required shared-video journeys beyond Instagram browser DMs | Start with one verified Instagram browser journey; evaluate native and YouTube exceptions separately | Adapter scope freeze |
| D-06 | First-release language and layout variants | English first, with the exact supported native/web account variants listed explicitly | Compatibility baseline |
| D-07 | Distribution and supported update channel | Select the distribution route and documented rule-update process | Release preparation |

## 13 References

These references inform architecture and constraints. Existing product behaviour is evidence for design choices, not proof that Dereel already implements a requirement.

1. Dereel product and UX discussion: combined native protection and filtered browser, shared policies, simple navigation and contextual setup.
2. Local SocialLite static-analysis report: [architecture and behaviour evidence](analysis/SocialLite-analysis.md). The examined artifact was version 3.0.4 and did not include its required installation splits.
3. Scrolless repository: <https://github.com/duartebarbosadev/Scrolless>. Reviewed source tree d4e8f81d459332231079f10a0b7315d2dcdab6c3; architectural references include accessibility detection, sessions and policy handlers. See the repository license and individual source notices before reuse.
4. Android permission guidance: <https://developer.android.com/training/permissions/requesting>.
5. Android accessibility service configuration: <https://developer.android.com/reference/android/accessibilityservice/AccessibilityServiceInfo>.

## 14 Revision history

| Version | Date | Change |
|---|---|---|
| 0.1 | 19 November 2026 | Initial proposed SRS covering the Android first release, acceptance criteria and open decisions |
