# Smart offline validation

Executed on BB5080 with JDK 21 and the repository Gradle/Android toolchain, based on upstream `3e660a84bde87281adee8f092b8af48719734916`.

```powershell
.\gradlew.bat :app:testLibreDebugUnitTest :app:lintLibreDebug :app:detekt :app:assembleLibreDebug --console=plain
git diff --check
```

| Check | Result |
| --- | --- |
| Unit tests | **36 passed**, zero failures/errors/skips: endpoint 8, manager/reconciliation 11, playback journal 2, selection/policy 6, transfer 9. |
| Debug APK | **Passed** `assembleLibreDebug`; package `org.jellyfin.mobile.debug`, version `0.0.0-dev.1`, min SDK 23. |
| APK signature | **Verified** with Android SDK `apksigner` (v1 and v2 signatures). |
| Android lint | **Zero introduced findings.** The full report matches upstream: 11 `ImpliedQuantity` errors in untouched translations and 66 warnings. Upstream retains `abortOnError=false`. |
| Detekt | **Zero introduced findings and zero findings in feature/test files.** The remaining 164 warnings match the exact upstream baseline. Upstream retains `ignoreFailures=true`. |
| Baseline comparison | An isolated, clean checkout at upstream `3e660a84` ran the same full online lint/Detekt tasks. Normalized `(tool, rule, relative file, message)` occurrence counts match: 241 total occurrences, 173 distinct keys. No unrelated upstream finding was edited or globally disabled. |
| Diff whitespace | **Passed** `git diff --check`. |
| Safety review | A separate read-only agent reviewed credential routing, ownership/deletion, scheduling and playback races. No remaining P0/P1 issue was reported. The repair race it identified was fixed and regression-tested: repair refuses active playback and invalidates the playable entry until the transfer completes. |
| Physical device / live server | **Not run.** No phone installation, live server credentials, private endpoint configuration, or network/VPN changes. |

The earlier compiler and test-fixture failures were corrected before this final successful run. Unit-test output includes dependency/JVM diagnostic warnings from OkHttp Android logging detection and MockK's Java agent; all test assertions passed. Build output includes upstream Gradle deprecation/native-symbol warnings. APK verification emits the usual v1 META-INF entry advisories while its whole-APK v2 verification succeeds.

APK: `app/build/outputs/apk/libre/debug/jellyfin-android-v0.0.0-dev.1-libre-debug.apk`

SHA-256: `9e65b180078b38dd0b95f81a430659fe2739615cb19a55fad34f39909f584e91`

Generated local reports are under `app/build/reports/tests/testLibreDebugUnitTest/`, `app/build/reports/lint-results-libreDebug.html`, and `app/build/reports/detekt/`. They are build artifacts and are not committed.

Two narrowly scoped analysis suppressions are documented at their declarations. `SmartOfflineDao` suppresses only `TooManyFunctions`: it is a cohesive, generated Room query contract, consistent with the repository's `UserDao` precedent. `SmartOfflineDiskSpace.available` suppresses only `UsableSpace`: admission deliberately uses existing free bytes plus a reserve, without assuming reclaimable cache; this also supports API 23. The network inspector retains a local compiler deprecation annotation for read-only enumeration of existing networks. No file-level rule disabling or global threshold changes were added.

The 73 findings previously present in feature/test files were resolved by splitting playback persistence, synchronization, transfer validation, storage ownership, replenishment and UI components; introducing named policy constants and structured inputs; and applying repository formatting. Additional integration findings were resolved without altering unrelated upstream issues. The new TLS test dependency is declared in the version catalog.

Device acceptance still needs: playback in airplane mode; resume after process termination/reboot; watch-state replay after reconnecting; cleanup while playback starts; cap exhaustion; metered Wi-Fi and VPN-underlay transitions; valid/mismatched/unreachable LAN endpoint behavior; and cancellation under Android background restrictions. See [setup and installation](smart-offline.md).
