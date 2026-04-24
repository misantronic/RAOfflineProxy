# RAOfflineProxy

An Android app that lets you earn **softcore RetroAchievements** through **RetroArch** without an internet connection.

> **Current release stage: alpha (`v1.0.0-alpha1`).** This is the first public prerelease and has not gone through formal QA.

RAOfflineProxy runs a tiny local proxy on your device. It sits between RetroArch and the RetroAchievements server, saving game and achievement data for offline use and queuing any achievements you unlock while offline. When you reconnect, queued awards are automatically sent to RetroAchievements.

> **Hardcore mode is not supported.** The app only works with softcore achievements.

## Features

- **Play offline** — game data, achievement lists, and unlock history are cached locally so everything works without Wi-Fi or mobile data
- **Automatic award sync** — achievements earned offline are queued and submitted to RetroAchievements as soon as you go online
- **Offline timestamps** — each queued award records the actual time you earned it, so your RA profile shows the real unlock time
- **Easy setup** — starting the proxy automatically patches RetroArch's config; stopping it restores your original settings
- **Scoped storage friendly** — uses SAF/folder access when direct config writes are not available; no all-files storage permission required
- **ROM scanning** — scan a folder or add individual ROMs to pre-cache everything you need before going offline
- **Auto-start on boot** — optionally starts in the background when your device boots
- **Background refresh** — cached data is refreshed hourly while you are online; stale entries older than 7 days are cleaned up automatically
- **Anti-tamper protection** — queued awards are cryptographically signed and chained on your device, and signatures are verified before awards are sent

## Requirements

- Android **8.0** or newer
- RetroArch installed (any variant)
- A RetroAchievements account with your credentials configured in RetroArch

## Quick Start

1. Install [RAOfflineProxy](https://github.com/misantronic/RAOfflineProxy/releases) and open it
2. Enter your RetroAchievements credentials in RetroArch
3. Start the proxy while online, then start any game in RetroArch and wait for a successful RA login
4. Cache your games from **Cached Games**, or just start them while online to cache them automatically
5. Play offline

## Important Shutdown Behavior

- Always stop sync before killing the app.
- On some devices, swiping the app away or crashing while the proxy is active does not reliably revert `retroarch.cfg` immediately.
- If that happens, reopen RAOfflineProxy once so it can clean up `retroarch.cfg`.

For detailed setup instructions, see the [documentation](https://raofflineproxy.com/installation.html).

## Documentation

Full documentation is available at [raofflineproxy.com](https://raofflineproxy.com).

## Contact / Feedback

If you want to report a bug, ask a question, or send feedback about RAOfflineProxy, use the [Contact / Feedback Form](https://forms.gle/XPRfWe2hAqzYy3JX9).

GitHub issue reports are also welcome at [github.com/misantronic/RAOfflineProxy/issues](https://github.com/misantronic/RAOfflineProxy/issues).

## License

Released under the [GNU GENERAL PUBLIC License](LICENSE).

---

RAOfflineProxy is approved by [RetroAchievements.org](https://retroachievements.org).
