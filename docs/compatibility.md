# Compatibility

Devices confirmed working by users or the developer.

| Device | Android version | Status |
|---|---|---|
| AYN Odin 2 Portal | Android 13 | ✅ Working |
| Ayaneo Pocket Air Mini | Android 11 | ✅ Working |

## Submit your device

Tested RAOfflineProxy on a device not listed here? [Fill out the Device Compatibility Form](https://forms.gle/m9hBXbPAx7KuLnmj9) — submissions are reviewed manually and may be added to the list above.

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
