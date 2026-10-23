# Offline subtitle validation

Validated on BB5080 on 2026-10-23, extending `dd8e32c4` on `feature/smart-offline-episodes`.

The smart downloader now saves supported external text subtitle tracks as local WebVTT. Existing managed videos receive missing subtitles during the next permitted check without repeating their video transfer. Native playback uses only complete owned subtitle files; unavailable external tracks are omitted from its subtitle menu. Video remains playable while subtitle retries are visible separately in episode activity. Account isolation, endpoint verification, exact HTTP consent, Wi-Fi policy, storage cap and watched-cleanup gates remain in force.

| Check | Result |
| --- | --- |
| JVM regression suite | 120 passed; zero failures, errors or skips. |
| JavaScript bridge regression suite | 13 passed; zero failures or skips. |
| Isolated API 36.1 emulator suite | 18 passed, including three new subtitle acceptance tests. Only `emulator-5580` was targeted. |
| Normal debug APK | `assembleLibreDebug` passed without the test-host property. No Compose test host in the distributable manifest. |
| APK signature | Existing debug certificate verified with v1/v2 signing; certificate SHA-256 `7d4ae6486e6a6bfe222c20002e4c2e53a992a5fe80edb8ab4676f13e41e7e581`. |
| Android lint | No introduced findings. Current offline run reports 11 existing translation errors and 63 existing warnings. Three dependency-update notices present in the previous online comparison are absent; no dependency versions changed. |
| Detekt | No introduced findings. 148 existing warnings remain, five fewer line-length occurrences than the previous feature build. The existing QueueManager function-count warning improves from 19 to 18 functions. No new suppressions or threshold changes. |

The new transfer tests use synthetic HTTP servers and invented credentials. They cover endpoint-bound authentication, redirect rejection, chunked and truncated bodies, malformed non-subtitle responses, policy interruption, bounded storage and retry. Resolver tests reject incomplete, foreign-download, remote-URL and unrelated-file subtitle records, hide unavailable tracks and preserve embedded subtitles and original stream indices.

Real Room acceptance tests backfill a previously completed video, verify that no video-download request occurs, reuse completed subtitle records, and remove only the owned episode and its sidecars. A separate storage/retry journey retains video playback during a 503 response and a full managed budget, then repairs the subtitle on retry. An unrelated local subtitle and another account's similarly named file survive cleanup.

The rendered acceptance test closes its loopback server before creating the player. The native queue's shared local MediaItem builder supplies file URIs for both video and English subtitles. Real Media3 emits `onRenderedFirstFrame` and an English `onCues` event; the captured PlayerView visibly displays the cue:

![Actual emulator playback of synthetic video with a local English subtitle](images/offline-english-subtitles-emulator.png)

The test uses the existing generated three-second H.264 color clip and a synthetic WebVTT caption. It proves local subtitle loading and rendering, rather than compatibility with every real media file or the production player menu. Physical-device verification and installation of this updated APK remain pending; no physical phone was operated during the subtitle implementation phase.

For acceptance after updating: open **Downloads > Smart offline > Save and queue check** while a permitted download route is available, wait for **Offline text subtitles ready** in the episode activity, then open the downloaded episode and select English in the native player's subtitle menu. If an episode is already playing, stop it before the check so its missing sidecar can be added. Existing videos and user data are retained. External bitmap subtitles remain unsupported; text conversion may simplify ASS styling.
