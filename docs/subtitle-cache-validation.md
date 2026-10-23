# Production subtitle cache correction

Physical-device acceptance of the previous selection fix failed. The downloaded episode played while the normal server was unavailable, but selected English subtitles did not appear. Read-only diagnostics showed a supported text track in the player's selection and a disabled text renderer. The episode was left paused and unwatched, with its downloads preserved.

The missing regression coverage was the application's actual media-source factory. Previous rendered tests built a default Media3 factory. Production uses a `CacheDataSource` whose cache-key callback assumed that every URI without an explicit key was a Jellyfin server route containing a UUID directory. Complete local subtitle files have account/item-based filenames rather than that route. Their key lookup threw `IllegalArgumentException: Invalid UUID string: null`. Media3 logged `Disabling track due to error`, removed the subtitle sample stream and kept video playing; this track failure did not invoke the player's fatal playback error callback.

The new acceptance journey obtains the actual `MediaSource.Factory` from the application's dependency container and enables the native player's decoder extensions. It starts with text disabled, prepares local video, selects English through the production selection helper, and waits for a real English `onCues` event. Before the cache fix it timed out; the emulator log recorded the UUID exception and disabled subtitle track. After the fix it passes and visibly renders the caption. The fixture server is closed before playback, and all files, accounts and captions are synthetic.

The cache-key callback now preserves explicit keys, uses the complete URI for `file:` and `content:` resources, and retains the previous server-route key behavior for remote resources. The video and each subtitle therefore have separate keys without parsing local filenames as server URLs. This changes neither stored download data nor network authentication or endpoint policies. No cache deletion or database migration is required.

Four additional regressions verify distinct local video/English/Chinese keys, document-provider identities, explicit-key precedence, and compatibility with existing remote video/subtitle download keys. The complete isolated emulator suite has 25 tests, alongside the existing 123 JVM and 13 JavaScript tests.

![Production cached playback displaying a complete local English subtitle](images/subtitle-production-cache-emulator.png)

The correction was implemented and tested without further physical-phone interaction after the user left Jellyfin. A new custom-build installation and visible-caption verification on that phone are still required. Emulator success is not a substitute for that acceptance check.
