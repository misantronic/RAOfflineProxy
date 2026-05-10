# Compatibility

Devices confirmed working by either the developer or user reports.

| Device | Android version | Status |
|---|---|---|
| AYN Odin 2 Portal | Android 13 | ✅ Working, dev tested |
| Ayaneo Pocket Air Mini | Android 11 | ✅ Working, dev tested |
| MagicX One 35 | Android 12 | ✅ Working, user tested |

## Submit your device

Tested RAOfflineProxy on a device not listed here? [Fill out the Device Compatibility Form](https://forms.gle/m9hBXbPAx7KuLnmj9) - submissions are reviewed manually and may be added to the list above.

## Why only RetroArch on Android?

RAOfflineProxy is only officially supported with **RetroArch on Android** because that is the environment it was built and tested for.

It depends on behavior that is specific to RetroArch:

- RetroArch exposes a **custom achievement server** setting that can be pointed at the local proxy
- RAOfflineProxy knows how to patch and restore RetroArch's `retroarch.cfg`
- it reads RetroArch's saved RetroAchievements credentials from the places Android RetroArch builds normally store them
- its request handling is built around the way RetroArch's RetroAchievements client talks to the API

Other emulators are not just a different frontend. They may use different config files, different credential storage, different request flows, or no compatible custom server override at all.

That means support for another emulator would require emulator-specific implementation, testing, and maintenance rather than a simple toggle.

For that reason, other emulators are currently **not supported**, even if they also expose RetroAchievements features.

Experimental Linux work in this repository is also centered around **RetroArch**.

## Why not implement this natively?

In an ideal world, offline RetroAchievements support would be built directly into the emulator.

In practice, that is much harder than it sounds.

On Android, a native implementation would require a separate custom build of RetroArch with this functionality added directly. On Linux-based handhelds, it would be even more difficult, because support there often depends on custom firmware or full operating system images. In many cases, that would mean rebuilding and maintaining large parts of the software stack just to add offline RetroAchievements support.

The other complication is that RetroAchievements depends on live server communication for things like game data, unlock state, session handling, and award submission.

RAOfflineProxy takes a different approach: it sits between RetroArch and RetroAchievements, caches what is needed locally, and syncs queued softcore awards later when you are back online.

That means the extra app is a tradeoff. But it also makes this possible today across more devices, without requiring custom emulator builds or system-level changes.
