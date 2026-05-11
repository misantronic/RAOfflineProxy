# Compatibility

Devices confirmed working by either the developer or user reports.

| Device | Android version | Status | Notes               |
|---|---|---|---------------------|
| AYN Odin 2 Portal | Android 13 | ✅ Working, dev tested |                     |
| Ayaneo Pocket Air Mini | Android 11 | ✅ Working, dev tested |                     |
| MagicX One 35 | Android 12 | ✅ Working, user tested |                     |
| Mangmi Air X | Android 14 | ❌ Not working, user tested | Stock, GammaOS_Next_v1.2 |
| Poco F6 | Android 16 | ❌ Not working, user tested | Derpfest Custom Rom |

## Submit your device

Tested RAOfflineProxy on a device not listed here? [Fill out the Device Compatibility Form](https://forms.gle/m9hBXbPAx7KuLnmj9) - submissions are reviewed manually and may be added to the list above.

## Which emulators are supported on Android?

RAOfflineProxy currently supports **RetroArch and Dolphin on Android**.

That support depends on emulator-specific behavior:

- the emulator must expose a compatible RetroAchievements server override or equivalent config path
- RAOfflineProxy must know how to patch and restore that emulator's config files
- it must know where that emulator stores RetroAchievements credentials on Android
- its request handling must match the way that emulator's RetroAchievements client talks to the API

Other emulators are not just a different frontend. They may use different config files, different credential storage, different request flows, or no compatible custom server override at all.

That means support for another emulator would require emulator-specific implementation, testing, and maintenance rather than a simple toggle.

For that reason, emulators beyond RetroArch and Dolphin are currently **not supported**, even if they also expose RetroAchievements features.

Experimental Linux work in this repository is also centered around **RetroArch**.

## Why not implement this natively?

In an ideal world, offline RetroAchievements support would be built directly into the emulator.

In practice, that is much harder than it sounds.

On Android, a native implementation would require separate emulator-specific builds with this functionality added directly. On Linux-based handhelds, it would be even more difficult, because support there often depends on custom firmware or full operating system images. In many cases, that would mean rebuilding and maintaining large parts of the software stack just to add offline RetroAchievements support.

The other complication is that RetroAchievements depends on live server communication for things like game data, unlock state, session handling, and award submission.

RAOfflineProxy takes a different approach: it sits between the emulator and RetroAchievements, caches what is needed locally, and syncs queued softcore awards later when you are back online.

That means the extra app is a tradeoff. But it also makes this possible today across more devices, without requiring custom emulator builds or system-level changes.
