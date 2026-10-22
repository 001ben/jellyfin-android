# Smart offline validation

Executed on BB5080 with JDK 21 and the repository Gradle/Android toolchain, based on upstream `3e660a84bde87281adee8f092b8af48719734916`.

```powershell
.\gradlew.bat :app:testLibreDebugUnitTest :app:lintLibreDebug :app:detekt :app:assembleLibreDebug --console=plain
git diff --check
```

| Check | Result |
| --- | --- |
| Unit tests | **63 passed**, zero failures/errors/skips: endpoint 11, manager 13, playback journal 2, policy 9, transfer 9, reconciler 4, settings 4, UI state 7, form draft 4. |
| Emulator instrumentation | **8 passed**, zero failures: seven rendered Compose UI tests and one actual Room v1-to-v2 migration test. Isolated API 36.1 emulator, fake accounts/callbacks, no live credentials or media operations. |
| Debug APK | **Passed** `assembleLibreDebug`; package `org.jellyfin.mobile.debug`, version `0.0.0-dev.1`, min SDK 23. |
| APK signature | **Verified** with Android SDK `apksigner` (v1 and v2 signatures). |
| Android lint | **Zero introduced findings.** The full report matches upstream: 11 `ImpliedQuantity` errors in untouched translations and 66 warnings. Upstream retains `abortOnError=false`. |
| Detekt | **Zero introduced findings and zero findings in feature/test files.** The remaining 164 warnings match the exact upstream baseline. Upstream retains `ignoreFailures=true`. |
| Baseline comparison | An isolated, clean checkout at upstream `3e660a84` ran the same full online lint/Detekt tasks. Normalized `(tool, rule, relative file, message)` occurrence counts match: 241 total occurrences, 173 distinct keys. No unrelated upstream finding was edited or globally disabled. |
| Diff whitespace | **Passed** `git diff --check`. |
| Safety review | Read-only review covers credential routing, ownership/deletion, scheduling and playback races. Repair refuses active playback and invalidates the playable entry until transfer completion. Trusted HTTP is explicitly opted in per address/account; watch writes and authoritative cleanup confirmation remain on the normal endpoint. |
| Data migration | **Passed on emulator.** Existing settings, selected series, managed file ownership/ETag/partial bytes and pending playback revisions survive the additive v1-to-v2 migration. HTTP trust defaults to empty for every account. No destructive fallback. |
| Physical device / live server | The earlier `f6176b61` APK was installed at the user's request and its package/version verified. **This revised build has not been installed or visually tested on the phone.** Diagnosis read the existing screen/display settings only. No sign-in, live downloads/cleanup, server changes or network/VPN changes were performed. |

The earlier compiler and test-fixture failures were corrected before this final successful run. Unit-test output includes dependency/JVM diagnostic warnings from OkHttp Android logging detection and MockK's Java agent; all test assertions passed. Build output includes upstream Gradle deprecation/native-symbol warnings. APK verification emits the usual v1 META-INF entry advisories while its whole-APK v2 verification succeeds.

APK: `app/build/outputs/apk/libre/debug/jellyfin-android-v0.0.0-dev.1-libre-debug.apk`

SHA-256: `d2779037f05f5808a4f21cf76ebb2c4d61ae83f426c2483d1aff501923976e35`

Size: 56,360,360 bytes. The ordinary debug APK excludes the empty Compose test activity; it is included only with `-PuiTests=true` for emulator fixtures. Signature verification confirms v1/v2 signatures with debug certificate SHA-256 `7d4ae6486e6a6bfe222c20002e4c2e53a992a5fe80edb8ab4676f13e41e7e581`.

## Rendered form verification

The previous `AlertDialog` form layout was reproduced from `f6176b61` with a fake view model and current string resources. Actual emulator screenshots showed the reported large blank region after scrolling and the old text-style save action. This temporary historical fixture was removed after recording the comparison; it is not packaged or committed.

The replacement full-screen dialog uses a bounded scrolling body and a fixed Material save button. Repeated top/bottom scrolling retains the content. Inline address errors are automatically brought into view while editing, rather than falling below the save bar. An initial visual run caught a large-text/keyboard window-pan gap despite passing button-visibility assertions; explicit dialog inset handling fixed it, and the regression now also requires the header to remain visible with the real on-screen keyboard open.

The final eight instrumentation checks cover repeated scrolling; invalid-address explanation/save blocking with the real keyboard; exact-address HTTP consent and consent invalidation; saving versus queued/running feedback while preserving edits; selected-series and scheduler-error visibility; the large-text keyboard fixture; unsaved-close confirmation; and actual migration/data preservation. Screenshots were captured from the emulator's rendered output and inspected directly, including the header/save/keyboard layout, visible validation message, HTTP warning and saved/queued state. No image generation or mocked screenshot output was used. Local evidence and logs are retained with this task.

Generated local reports are under `app/build/reports/tests/testLibreDebugUnitTest/`, `app/build/reports/lint-results-libreDebug.html`, and `app/build/reports/detekt/`. They are build artifacts and are not committed.

Two narrowly scoped analysis suppressions are documented at their declarations. `SmartOfflineDao` suppresses only `TooManyFunctions`: it is a cohesive, generated Room query contract, consistent with the repository's `UserDao` precedent. `SmartOfflineDiskSpace.available` suppresses only `UsableSpace`: admission deliberately uses existing free bytes plus a reserve, without assuming reclaimable cache; this also supports API 23. The network inspector retains a local compiler deprecation annotation for read-only enumeration of existing networks. No file-level rule disabling or global threshold changes were added.

The 73 findings previously present in feature/test files were resolved by splitting playback persistence, synchronization, transfer validation, storage ownership, replenishment and UI components; introducing named policy constants and structured inputs; and applying repository formatting. Additional integration findings were resolved without altering unrelated upstream issues. The new TLS test dependency is declared in the version catalog.

Device acceptance still needs: playback in airplane mode; resume after process termination/reboot; watch-state replay after reconnecting; cleanup while playback starts; cap exhaustion; metered Wi-Fi and VPN-underlay transitions; valid/mismatched/unreachable LAN endpoint behavior; and cancellation under Android background restrictions. See [setup and installation](smart-offline.md).
