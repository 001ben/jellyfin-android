# Smart offline episode downloads

Open **Downloads → Smart offline** after signing in. The full-screen form has a fixed **Save and queue check** button, inline validation, live scheduler status and a selected-series list. Enable the feature, choose 1-20 episodes per series (default 3), choose a permitted connection, then search for and select series. Series selections save immediately; other settings require the save button. Settings and subscriptions are scoped to the signed-in server and user. Completed videos use the existing native offline player.

The save bar distinguishes unsaved edits, saving, saved settings and scheduler acceptance. **Check queued** means Android accepted a request, not that it ran. **Check running** is observed from WorkManager; the last worker report is labeled separately. Opening the form only observes local state. Closing with unsaved settings asks whether to discard them. Search results belong to the submitted query; selected series remain visible independently.

Downloads require either an optional verified LAN endpoint or explicit permission to use the configured remote endpoint. Both automatic downloading and watched cleanup start disabled. Unmetered Wi-Fi is required by default. Disabling that option permits other connected networks, including metered/roaming networks. No network or VPN settings are modified.

## Selection, storage and cleanup

The selector orders unwatched episodes by season and episode number, omits duplicates, specials, virtual/missing episodes and future premiere dates, and maintains the next N slots. A manual download already occupying a slot is retained and never adopted for cleanup. Episodes need a known positive media-source size; an unknown-size or oversized episode is skipped rather than downloaded without a budget. This can leave fewer than N episodes available.

The default cap is 10 GiB, configurable from 1–100 GiB. Admission counts every file in this feature's private storage, including partial and retained files and files belonging to other accounts; an account's configured cap can therefore conservatively pause it when another account already uses the space. Each transfer also checks actual response size, bytes written and a 256 MiB disk reserve. Lowering a cap pauses additions until usage falls below it; it does not evict unwatched videos. Manual downloads are outside this budget and cleanup ownership.

**Automatically remove watched managed downloads** is opt-in. A fresh server `Played` state, no pending local playback event and no active playback lease are required. Cleanup deletes only the exact private file recorded by this feature and validates server/user/item/path ownership before removing its download record. It never calls a server media-delete API or traverses the user's chosen download folder. Watched partial files can also be removed. Unsubscribing keeps existing files; disable the feature to stop recurring downloads and cleanup.

The standard Downloads delete action works for managed files, respecting active playback and pending synchronization. Choosing to retain a file keeps its ownership record and budget usage. A still-subscribed series may download or register it again on a later pass.

## Home LAN routing

Configure the Jellyfin base URL, including any reverse-proxy path, such as `https://jellyfin.home.example/jellyfin`. HTTPS endpoints must have valid TLS trusted by Android and resolve entirely to local addresses over a non-VPN unmetered Wi-Fi network. Embedded credentials, query strings and fragments are rejected. The normal signed-in server address is kept separately and can continue to use Tailscale.

An optional **Trust HTTP for this address** exception supports a literal RFC1918 private IPv4 address with an explicit port. It starts disabled, displays an address-specific warning, and requires **Trust this address** followed by saving. Consent is stored for that exact canonical URL and account; changing the URL clears it. Hostnames, public IPs, Tailscale CGNAT addresses, loopback, link-local and IPv6 are rejected for HTTP. Existing settings migrate without granting HTTP trust.

**HTTP sends the Jellyfin session token and media unencrypted over the selected Wi-Fi network.** A matching public Jellyfin server ID is a consistency check, not cryptographic authentication: a hostile local network can impersonate the server or alter content. Use the exception only if that risk is acceptable. HTTP is never inferred from a failed HTTPS request, and certificate verification is never relaxed.

On initial setup a credential-free public identity request to the existing remote server pins its Jellyfin server ID. Each LAN pass probes `/System/Info/Public` without credentials and requires the same ID before authenticated requests. HTTPS also verifies TLS; trusted HTTP retains the risk described above. DNS results are held for that pass; HTTP additionally requires the resolved numeric address to match the explicitly approved address. Media and selection metadata use that Wi-Fi network's socket factory and DNS, bypassing proxies without changing system routing. Redirects and TLS downgrades are disabled. If the LAN probe fails, the configured remote endpoint is used only when **Allow downloads through the configured remote endpoint** is selected; its existing transport/VPN route is retained.

With trusted HTTP, playback progress writes and the fresh watched-state confirmation required for cleanup stay on the normal server connection. If that connection is unavailable, cleanup is deferred and the watch outbox remains pending, while reachable HTTP LAN downloads can continue. HTTP responses cannot alone authorize watched-file deletion.

Wi-Fi-only remote fallback additionally checks the active route's Wi-Fi/unmetered capabilities. A spare connected Wi-Fi network cannot authorize cellular traffic. VPN implementations that do not expose their Wi-Fi underlay conservatively wait. VPN lockdown may prevent direct LAN sockets; the app respects that restriction. SSIDs, location access and live server credentials are not required to configure the feature's code.

## Scheduling and recovery

Unique WorkManager jobs are requested when saved, after offline playback synchronization, and approximately hourly. Android may defer them for Doze, battery restrictions, missing network constraints or job quotas. The screen observes queued, running, retry and terminal states without starting a check itself. They require storage-not-low, use exponential retry backoff and show a cancellable foreground download notification. Each pass is bounded to eight minutes; subsequent passes resume safely. Cancel stops the current pass; disable the feature to stop recurrence. Android 16 foreground-worker quotas can delay further work.

Partial bytes and strong ETags are durable. Resumes use `Range` plus `If-Range`; changed content restarts from zero, invalid range/validator responses invalidate the resume validator, and a matching HTTP 416 can recover a completed file after a process death. Servers without a strong ETag restart interrupted files. Short bodies never become playable download records. Files become visible only after a verified complete transfer. No transcoding or external-subtitle fetching is added; embedded tracks play through the existing native player.

Local playback snapshots are serialized and ordered when captured. The durable outbox includes server/user/item identity, survives outages and manual removal, and never discards watched state after an arbitrary retry count. Acknowledgements delete only the sent revision. Successful stop reporting is remembered separately from marking a fully finished episode played. Partial stops leave watched-state decisions to Jellyfin; the client never explicitly unmarks an episode. Pending local finishes are excluded from replenishment selection while cleanup waits for server confirmation. Resume position is saved locally. A separate hourly connected-network job recovers pending synchronization after app/process restart.

This is an independent implementation informed by the approach in [PR 2169](https://github.com/jellyfin/jellyfin-android/pull/2169), not a merge or cherry-pick of that unmerged proposal. A separate Room database avoids changing the existing download schema and keeps later reconciliation with that PR possible.

## Build and install yourself

Use JDK 21, the repository Gradle wrapper and the Android SDK required by `gradle/libs.versions.toml`:

```powershell
.\gradlew.bat :app:testLibreDebugUnitTest :app:lintLibreDebug :app:assembleLibreDebug
```

Find the APK under `app/build/outputs/apk/libre/debug/`. The debug application ID is `org.jellyfin.mobile.debug`, so it can coexist with the official release. Copy the APK to the phone and open it, granting installation permission to your chosen file manager if requested. Alternatively, run `adb install -r <path-to-apk>` yourself on your authorized device. Future debug updates need the same signing key. This task does not install anything on a phone.

Unit tests use temporary files and local HTTP/HTTPS fixtures, never a live server. They cover ordered selection/replenishment, cleanup gates and ownership, storage limits, interrupted/changed transfers, retries, playback ordering, LAN identity/TLS/redirect/fallback policy, and metadata through a verified LAN route. Physical-device scheduling, VPN-underlay behavior and full offline playback with real media still require device testing.

For isolated emulator UI tests, build both APKs with `-PuiTests=true`. This includes an empty Compose test host only in that test build; ordinary debug APKs do not package it. Instrumentation tests use fake accounts and callbacks, never live credentials, scheduling, media downloads or cleanup. The Room migration test seeds an independent v1 database and validates the actual v2 migration. Target a specific emulator serial when installing/running tests; do not run connected tests indiscriminately while a user's phone is attached.
