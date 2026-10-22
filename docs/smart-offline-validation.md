# Smart offline integration validation

Executed on BB5080 with JDK 21 and the repository Gradle/Android SDK toolchain. Upstream comparison uses `3e660a84bde87281adee8f092b8af48719734916`; implementation remains on the personal fork's `feature/smart-offline-episodes` branch.

```powershell
.\gradlew.bat :app:testLibreDebugUnitTest :app:lintLibreDebug :app:detekt :app:assembleLibreDebug --console=plain
node --test app/src/test/js/*.test.mjs
git diff --check
```

| Check | Final result |
| --- | --- |
| JVM unit tests | **111 passed**, zero failures/errors/skips. Includes original selector/cap/retry/LAN/TLS/ownership/watch-sync tests plus account observation, bridge boundaries, catalog projection, deselection interruption, artwork, local-file validation and mixed playback queues. |
| JavaScript tests | **13 passed**, zero failures/skips: supported card/list/detail fixtures, account/server changes, stale/recycled rows, bounded replies, explicit actions/errors and synchronous player selection. |
| Emulator instrumentation | **15 passed**, zero failures: 9 rendered Compose journeys, 2 real Room migration checks, 2 WebView rendering/touch fixtures and 2 actual Room/Media3 local-playback checks. Only isolated `emulator-5580` (API 36.1) was accessed. |
| Distributable APK | **Passed** ordinary `assembleLibreDebug`, without `-PuiTests=true`. Package `org.jellyfin.mobile.debug`, version `0.0.0-dev.1`, code 1, minimum SDK 23. No empty test-host activity in its manifest. |
| Signature | **Verified** APK v1/v2 signatures with the existing debug certificate; certificate SHA-256 `7d4ae6486e6a6bfe222c20002e4c2e53a992a5fe80edb8ab4676f13e41e7e581`. |
| Android lint | **Zero introduced findings.** The existing 11 `ImpliedQuantity` errors in untouched translations and 66 warnings remain. Upstream config retains `abortOnError=false`. |
| Detekt | **Zero introduced findings.** 153 existing warnings remain; 11 baseline formatting occurrences disappeared within modified files. No global thresholds or new broad suppressions were introduced. |
| Normalized comparison | Compared `(tool, rule, relative file, message)` occurrence counts against a clean upstream run: **0 added, 11 removed**, 230 remaining occurrences and 167 keys. |
| Whitespace / secrets | `git diff --check` passed. Fixtures use invented accounts/endpoints and generated media; no live credentials, private endpoint configuration or device details are committed. |
| Physical phone / live server | **Not accessed or installed during this integration phase**, as requested. No sign-in, server/network/security changes, live downloads or user-media cleanup were performed. |

APK: `app/build/outputs/apk/libre/debug/jellyfin-android-v0.0.0-dev.1-libre-debug.apk`

SHA-256: `e7d5f2311c2f2e27436357e478bd32442cdddea3adbce75cf1a59dcea6308dd4`

Size: **58,936,592 bytes**.

## Rendered and playback evidence

Actual emulator captures were inspected: `11-selected-poster.png`, `11-web-context-fixture.png`, `12-web-context-tapped-fixture.png`, `12-pending-watch-sync.png`, `13-cleanup-and-removed.png` and `14-local-video-fixture.png`. The selected poster is a local purple fixture; web layouts and statuses are synthetic, clearly labeled, and make no server requests. Genuine injected touches open the fixture manage/play actions; synthetic DOM clicks do not. Native activity rows distinguish watched/pending sync, separately eligible cleanup and removed copies.

The local video test uses a generated 2,016-byte, three-second H.264 Constrained Baseline clip (96x64). A real Media3 PlayerView awaits `onRenderedFirstFrame` and positive playback position through the same verified file URI used by the native route; the unavailable remote callback is never called. Its isolated Room outbox retains account/item identity and unacknowledged watch progress. Partial/repairing files and another account cannot become playable. This proves fixture playback, not full live-server Browse-to-Play or real-media compatibility.

Two Room tests validate additive v1-to-v3 and v2-to-v3 migration, preserving settings, exact-address HTTP consent, series, managed ownership/bytes/ETag and pending playback revisions. New server-watched confirmations default false. Successful-removal history is bounded per account; an absent or never-created file cannot invent a removal event.

The first rendered pass found fixture problems and they were corrected: the test host needed the app XML theme for PlayerView, the lazy activity section needed to be realized before text lookup, and the unsaved-dialog journey tapped while Android's native confirmation window was still fading after Compose semantics removed it. The latter now waits for that exact native window to disappear and editor focus to return, then performs a real tap and verifies saved count 5. Production form behavior was not changed to mask the test. Historical and diagnostic captures are retained locally separately from current acceptance evidence.

Initial SDK test-fixture construction errors, two non-Unit JUnit methods and three newly reported WebView feature-check lint findings were corrected before the successful full runs. Dependency/JVM logging and Gradle/native-symbol deprecation warnings remain diagnostic only. Existing narrow DAO/DiskSpace suppressions are documented at their declarations; no new file-level disabling was added.

## Practical limits and device acceptance

The server supplies Jellyfin Web. Enhancements feature-detect recognized 10.11 classic/React card/list attributes and classic detail controls; custom themes or future layouts can omit them. Cold offline web-library browsing is not implemented. Native Downloads remains the offline entry point. Web/external player selection needs a fresh account-bound native snapshot; before that, the configured player remains selected and explicit **Play downloaded** is available on recognized complete items. SyncPlay/casting retain existing routes. Native local media resolution avoids playback metadata requests, while existing nonblocking preference/segment requests may still be attempted.

Still requires the user's later real-device/live-server acceptance: library browsing and show context on the deployed server/theme; real-media codecs and embedded tracks; airplane mode/cold start; process termination/reboot; watch replay and cleanup races after reconnecting; storage exhaustion; metered Wi-Fi/VPN-underlay changes; reachable/mismatched/unreachable LAN routes; and Android background restrictions. No phone update is authorized for this phase. See [setup, routing and self-install instructions](smart-offline.md).
