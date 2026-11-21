# Architecture

```
┌──────────────────────────── :app (Android) ─────────────────────────────┐
│  UI (Compose)            Native adapter              Browser adapter       │
│  Home/Activity/Settings  DereelAccessibilityService  BrowserActivity       │
│  Platform, Onboarding    AccessibilityNodeUiNode     WebView + page rules  │
│        │                 OverlayController           (assets/rules/*.js)   │
│        │                        │                          │               │
│  StatusMonitor ──probes──►      │                          │               │
│  (accessibility, apps,          ▼                          ▼               │
│   WebView features)    ┌──────────── :core (pure Kotlin, no Android) ────┐ │
│        │               │ NativeSurfaceDetector   BrowserNavigationController│
│        │               │ DenyEpisodeController   WebUrlClassifier/routes │ │
│        │               │ LayoutRecognitionTracker SharedItemExceptionTracker│
│        │               │ BridgeProtocol           SharedLinkParser       │ │
│        │               │            ▼                    ▼               │ │
│        │               │        ProtectionCoordinator  (one per process) │ │
│        │               │         │            │              │           │ │
│        │               │   PolicyEngine   UsageTracker   GrantManager    │ │
│        └──────────────►│   StatusEvaluator                               │ │
│                        └─────────────────────────────────────────────────┘ │
│  Room (app-private): platform_config, app_preferences, usage_segment,      │
│                      temporary_grant                                       │
└────────────────────────────────────────────────────────────────────────────┘
```

## Separation of concerns (as requested)

| Concern | Where | Notes |
|---|---|---|
| Shared policy decisions | `core/policy/PolicyEngine`, `core/coordinator/ProtectionCoordinator` | POL-01 rule order lives in one function. The coordinator is the only object adapters talk to (DATA-02). |
| Native detection and enforcement | `core/nativeapp/*` (pure logic), `app/nativeapp/*` (Android) | Detectors work on `UiNode`, which has no text. Episode and cooldown logic is pure and unit-tested. |
| Browser platform adapters | `core/web/*` (routes, controller, URL validation), `app/browser/*` (WebView), `app/src/main/assets/rules/*` | The native navigation guard is authoritative. Page rules only hide entry points and stop taps early. |
| Usage accounting and persistence | `core/usage/UsageTracker`, `app/data/*` (Room) | One open session per platform. Monotonic accrual, 5 s checkpoints, midnight split, recovery. |
| UI and operational status | `core/status/StatusEvaluator`, `app/status/StatusMonitor`, `app/ui/*` | Status comes from live probes, never from assumptions. |

## Key flows

**Native denial.** An accessibility event (from an app with a native rule set: Instagram, YouTube, Facebook Lite or Snapchat) is throttled to
40–150 ms. Detection runs on the foreground `TYPE_APPLICATION` window. Keyboards, system UI and
Dereel overlays are ignored. The flow is then `coordinator.report(platform, NATIVE_APP,
SHORT_VIDEO)`, then `Deny`, then `DenyEpisodeController`. Its first answer is one Back plus a
banner. If the surface is still there after the ≥1 s cooldown, the answer is a cover with "Go
back". Leaving the app, screen off, lock, removing the platform or the service stopping all clear
the observation and overlays.

**Allowance running out.** While viewing is allowed, the coordinator schedules a re-evaluation at
`remainingAllowanceMs`. At zero, the decision flips to `Deny(ALLOWANCE_EXHAUSTED)` and usage stops.
Adapters observing `coordinator.decisions` then apply the denied-content action. Both methods share
the one balance because they share the coordinator and tracker.

**Browser navigation.** `shouldOverrideUrlLoading` handles links, redirects and location changes
before they load. `onPageStarted` and `doUpdateVisitedHistory` handle in-page routes, refresh,
Back and Forward. All of them go through `BrowserNavigationController`. A denied route goes back
once, or loads the last safe page. A loop guard (3 corrections in 5 s) turns repetition into an
error state. Page rules are injected at document start, for the platform's top-level origins only.

**Shared reel (Instagram inside Dereel).** A conversation route followed by a user-initiated move
to `/reel/<code>` creates `SharedItemMatch(Conversation, code)`. The same code (re-render, replay,
query change) keeps it. A different code or the reels feed ends it and returns to the
conversation. Leaving, an account page, sign-out, closing or a process restart also end it.

## Security boundaries

- Bridge: `WebViewCompat.addWebMessageListener` with exact origin rules. The app also checks the
  main frame and origin, and `BridgeProtocol.parse` rejects anything other than two message types
  with strict fields. No page message can navigate outside the platform, open intents or touch
  files.
- WebView: no file or content access, mixed content never, no third-party cookies, TLS errors
  cancelled, no pop-ups, permissions denied, debugging only in debug builds.
- External links and app schemes: never automatic. `intent:` and custom schemes are refused, and
  web links need confirmation.
- Storage: app-private Room. Backup and device transfer are excluded. Logs carry exception types
  only.

## Platforms and opening methods

`Capabilities.methods(platform)` is the single list of which opening methods exist. Everything
else follows from it, and `CapabilitiesTest` checks that it matches the adapters that exist:

| Platform | Native rule set (`NativeRules`) | Browser hosts and page rules (`PlatformWeb`, `assets/rules`) |
|---|---|---|
| Instagram | `ig-native` | `instagram.js` |
| YouTube | `yt-native` | `youtube.js` |
| Facebook | `fb-lite-native` (Facebook Lite; the regular app has none) | `facebook.js` |
| Snapchat | `sc-native` | none |

A platform with one method has it preselected in `PlatformConfig`, is never asked the
opening-method question, and never shows status, recovery actions or settings for the method it
lacks. A platform may have several supported apps (`Platform.nativePackages`), each with its own
rule set. The accessibility service builds one detector per supported app, and its
`packageNames` must list exactly those apps (`NativeScopeTest`). `BrowserActivity` refuses to
start for a platform without a browser method.
