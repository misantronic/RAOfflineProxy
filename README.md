<h1>
  <img src="https://d3bywedspcdj3v.cloudfront.net/logo.png" alt="RAOfflineProxy logo" width="40">
  RAOfflineProxy
</h1>

RAOfflineProxy is a local proxy that lets you earn **softcore RetroAchievements** without an internet connection.

It currently supports **RetroArch and Dolphin on Android**, and **KNULLI** plus **Onion** (experimental) for Linux.

> **Current release stage: alpha (`v1.0.1-alpha4`).** This is a public prerelease and has not gone through formal QA.

RAOfflineProxy runs a tiny local proxy on your device. It sits between supported emulators and the RetroAchievements server, saving game and achievement data for offline use and queuing any achievements you unlock while offline. When you reconnect, queued awards are automatically sent to RetroAchievements.

> **Hardcore mode is not supported.** The app only works with softcore achievements.

## Platform Support

### Android

- Supported emulators: **RetroArch** and **Dolphin**
- Status: primary supported platform
- Setup guide: [Android setup](https://raofflineproxy.com/installation.html)

### Linux

- Supported emulator: **RetroArch**
- Supported targets: **KNULLI** (alpha), **Onion** (experimental)
- Setup guide: [Linux setup](https://raofflineproxy.com/linux-support/)

## Features

- **Play offline**: game data, achievement lists, and unlock history are cached locally so everything works without Wi-Fi or mobile data
- **Automatic award sync**: achievements earned offline are queued and submitted to RetroAchievements as soon as you go online
- **Offline timestamps**: each queued award records the actual time you earned it, so your RA profile shows the real unlock time
- **Easy setup**: starting the proxy automatically patches supported emulator configs; stopping it restores your original settings
- **Scoped storage friendly**: uses SAF/folder access when direct config writes are not available; no all-files storage permission required
- **ROM scanning**: scan a folder or add individual ROMs to pre-cache everything you need before going offline
- **Dolphin support**: patches Dolphin's `RetroAchievements.ini`, caches Dolphin `achievementsets`, and supports offline GameCube/Wii flows
- **GameCube / Wii manual caching**: manual hashing now works for GameCube `.iso` / `.gcm` / `.rvz`, Wii `.iso` / `.rvz`, and Wii `.wad` files
- **Linux support**: KNULLI is available in alpha, and Onion is available experimentally
- **Auto-start**: optionally starts automatically on supported platforms
- **Background refresh**: cached data is refreshed hourly while you are online; stale entries older than 7 days are cleaned up automatically
- **Anti-tamper protection**: queued awards are cryptographically signed and chained on your device, and signatures are verified before awards are sent

## Requirements

### Android

- Android **8.0** or newer
- RetroArch and/or Dolphin installed
- A RetroAchievements account with your credentials configured in every emulator you plan to use

### Linux

- A supported Linux target: **KNULLI** or **Onion**
- RetroArch available on the device
- A RetroAchievements account configured in RetroArch
- For Onion, the system clock must be correct

## Quick Start

### Android

1. Install [RAOfflineProxy](https://github.com/misantronic/RAOfflineProxy/releases) and open it.
2. Start the proxy so RAOfflineProxy can import your saved RetroAchievements login from your emulator config.
3. Cache your games from **Cached Games**, or start them in RetroArch or Dolphin while online to cache them automatically.
4. Play offline

### Linux

1. Download the correct Linux release from [GitHub Releases](https://github.com/misantronic/RAOfflineProxy/releases).
2. Follow the [Linux setup guide](https://raofflineproxy.com/linux-support/installation.html) and choose the `KNULLI` or `Onion` tab for your device.
3. Start the proxy while online.
4. Launch a game once in RetroArch so its data is cached.
5. Play offline

## Important Shutdown Behavior

These notes apply to the Android app.

- Always stop sync before killing the app.
- On some devices, swiping the app away or crashing while the proxy is active does not reliably revert the patched emulator config immediately.
- If that happens, reopen RAOfflineProxy once so it can clean up the patched emulator config.

For detailed setup instructions, see the documentation below.

## Documentation

Full documentation is available at [raofflineproxy.com](https://raofflineproxy.com).

- [Introduction](https://raofflineproxy.com/introduction.html)
- [Android setup](https://raofflineproxy.com/installation.html)
- [Linux overview](https://raofflineproxy.com/linux-support/)
- [Linux installation](https://raofflineproxy.com/linux-support/installation.html)
- [Android emulator patching](https://raofflineproxy.com/cfg-patching.html)
- [Linux emulator patching](https://raofflineproxy.com/linux-support/cfg-patching.html)

## Contact / Feedback

If you want to report a bug, ask a question, or send feedback about RAOfflineProxy, use the [Contact / Feedback Form](https://forms.gle/XPRfWe2hAqzYy3JX9).

GitHub issue reports are also welcome at [github.com/misantronic/RAOfflineProxy/issues](https://github.com/misantronic/RAOfflineProxy/issues).

[![Patreon](https://img.shields.io/badge/dynamic/json?url=https%3A%2F%2Fwww.patreon.com%2Fapi%2Fcampaigns%2F15993692&query=data.attributes.patron_count&suffix=%20Patrons&color=FF5441&label=Patreon&logo=Patreon&logoColor=FF5441&style=for-the-badge)](https://patreon.com/misantronic)   [![ko-fi](https://ko-fi.com/img/githubbutton_sm.svg)](https://ko-fi.com/M4M81YDHD4)

## License

Released under the [GNU GENERAL PUBLIC License](LICENSE).

---

RAOfflineProxy is approved by [RetroAchievements.org](https://retroachievements.org).
