# Subtitle selection correction

The native player could remember English before its text track was prepared. That failed selection changed the source's remembered choice; a later attempt returned success solely because the choice matched, without enabling the text renderer. The menu also checked the choice before receiving a successful result. Downloaded subtitle files and an English menu check therefore did not prove captions were enabled.

The selection helper now checks the live player and prepared text group first, applies the selector, and only then remembers the selection. Selecting an already remembered track reapplies the override and enables text. The menu checks a choice only after success; unavailable tracks produce a visible retry message. Embedded selection also verifies that the matched group is text. Audio selection and subtitle burn-in restart behavior are retained.

Three JVM regression tests failed against the previous implementation and pass after the correction: preparation-time failure must not remember English, repeating an unavailable selection must not report success, and a remembered choice cannot substitute for a live player.

Two new real Media3 emulator journeys call the production selection function: retry English after an early preparation-time failure, and repair an already remembered English choice with text disabled. Both wait for an actual English `onCues` event. Their loopback subtitle server is closed before playback; video and subtitle use complete local files. The captured PlayerView visibly displays the synthetic English cue:

![English caption after reselecting a remembered track with text disabled](images/subtitle-selection-repaired-emulator.png)

Validation on BB5080: 123 JVM tests, 13 JavaScript tests, and the complete 20-test isolated emulator suite pass. The normal debug APK builds without the Compose test host and verifies with the existing debug signing certificate. Detekt has 146 existing findings, two fewer than the previous subtitle build; lint retains 11 existing translation errors and 63 existing warnings. No new analysis findings or suppressions were introduced.

This confirms a reproducible selection bug and the two emulator recoveries. It does not yet prove that every cause of the reported physical-phone rendering failure is resolved. Installation and visible-caption verification of this corrected build on that phone remain pending. No physical phone was operated during this correction. Existing videos, sidecars, settings and watch progress are preserved.
