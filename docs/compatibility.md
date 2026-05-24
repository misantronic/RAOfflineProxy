# Compatibility

Devices confirmed working by either the developer or user reports.

| Device                                                                                                              | Version    | Status                                              | Notes                                                                                                                                                      |
|---------------------------------------------------------------------------------------------------------------------|------------|-----------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------|
| <span style="white-space: nowrap;">AYN Thor</span>                                                                  | <span style="white-space: nowrap;">Android 13</span> | <span style="white-space: nowrap;">✅ Working</span> | User tested                                                                                                                                                |
| <span style="white-space: nowrap;">AYN Odin 2 Portal</span>                                                         | <span style="white-space: nowrap;">Android 13</span> | <span style="white-space: nowrap;">✅ Working</span> | Dev tested                                                                                                                                                 |
| <span style="white-space: nowrap;">Anbernic Arc D</span>                                                            | <span style="white-space: nowrap;">Android 11</span> | <span style="white-space: nowrap;">✅ Working</span> | User tested                                                                                                                                                |
| <span style="white-space: nowrap;">Ayaneo Pocket Air Mini</span>                                                    | <span style="white-space: nowrap;">Android 11</span> | <span style="white-space: nowrap;">✅ Working</span> | Dev tested                                                                                                                                                 |
| <span style="white-space: nowrap;">MagicX One 35</span>                                                             | <span style="white-space: nowrap;">Android 12</span> | <span style="white-space: nowrap;">✅ Working</span> | User tested                                                                                                                                                |
| <span style="white-space: nowrap;">Retroid Pocket Classic</span>                                                    | <span style="white-space: nowrap;">Android 14</span> | <span style="white-space: nowrap;">✅ Working</span> | User tested                                                                                                                                                |
| <span style="white-space: nowrap;">Mangmi Air X</span><br><span style="white-space: nowrap;">Anbernic RG477M</span> | <span style="white-space: nowrap;">Android 14</span> | ⚠️ Only with a [workaround](/manual-emulator-setup) | User tested; reports indicate Android 14 blocks granting access to scoped storage. See [issue #4](https://github.com/misantronic/RAOfflineProxy/issues/4). |

## Submit your device

Tested RAOfflineProxy on a device not listed here? [Fill out the Device Compatibility Form](https://forms.gle/m9hBXbPAx7KuLnmj9) - submissions are reviewed manually and may be added to the list above.

## Which emulators are supported on Android?

RAOfflineProxy currently supports **RetroArch and Dolphin on Android**.

That support depends on emulator-specific behavior:

- The emulator must expose a compatible RetroAchievements server override or equivalent config path
- RAOfflineProxy must know how to patch and restore that emulator's config files
- It must know where that emulator stores RetroAchievements credentials on Android
- Its request handling must match the way that emulator's RetroAchievements client talks to the API

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
