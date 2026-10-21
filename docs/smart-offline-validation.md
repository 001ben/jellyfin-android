# Smart offline validation

Executed on BB5080 with JDK 21 and the repository Gradle/Android toolchain, based on upstream `3e660a84bde87281adee8f092b8af48719734916`.

```powershell
.\gradlew.bat :app:testLibreDebugUnitTest :app:lintLibreDebug :app:detekt :app:assembleLibreDebug --console=plain
git diff --check
```

| Check | Result |
| --- | --- |
| Unit tests | **28 passed**, zero failures/errors/skips: endpoint 5, manager/journal 8, selection/policy 6, transfer 9. |
| Debug APK | **Passed** `assembleLibreDebug`; package `org.jellyfin.mobile.debug`, version `0.0.0-dev.1`, min SDK 23. |
| APK signature | **Verified** with Android SDK `apksigner` (v1 and v2 signatures). |
| Android lint | Task completed with the repository's existing `abortOnError=false`. **Not clean:** 11 `ImpliedQuantity` errors in untouched translation files and 71 warnings. The new feature contributes three `UsableSpace` advisory warnings; its conservative disk checks intentionally do not assume reclaimable cache space. |
| Detekt | Task completed with existing `ignoreFailures=true`. **Not clean:** 265 reported findings across the repository, including 73 in the new feature/test files. Remaining findings include style, magic-number and complexity diagnostics. |
| Diff whitespace | **Passed** `git diff --check`. |
| Safety review | A separate read-only agent reviewed credential routing, ownership/deletion, scheduling and playback races. Its concrete defects were addressed; the final review reported no remaining P0/P1 issue. |
| Physical device / live server | **Not run.** No phone installation, live server credentials, private endpoint configuration, or network/VPN changes. |

The earlier compiler and test-fixture failures were corrected before this final successful run. Unit-test output includes dependency/JVM diagnostic warnings from OkHttp Android logging detection and MockK's Java agent; all test assertions passed. Build output includes upstream Gradle deprecation/native-symbol warnings. APK verification emits the usual v1 META-INF entry advisories while its whole-APK v2 verification succeeds.

APK: `app/build/outputs/apk/libre/debug/jellyfin-android-v0.0.0-dev.1-libre-debug.apk`

SHA-256: `b20ee1f4bcd4036bf46d6c31ad63520654bb395ef3bc36760f6ebb6c6e7434ed`

Generated local reports are under `app/build/reports/tests/testLibreDebugUnitTest/`, `app/build/reports/lint-results-libreDebug.html`, and `app/build/reports/detekt/`. They are build artifacts and are not committed.

Device acceptance still needs: playback in airplane mode; resume after process termination/reboot; watch-state replay after reconnecting; cleanup while playback starts; cap exhaustion; metered Wi-Fi and VPN-underlay transitions; valid/mismatched/unreachable LAN endpoint behavior; and cancellation under Android background restrictions. See [setup and installation](smart-offline.md).
