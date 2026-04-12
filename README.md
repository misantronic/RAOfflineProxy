# RAOfflineProxy

An Android app that lets you earn **softcore RetroAchievements** through **RetroArch** without an internet connection.

> **Current release stage: alpha (`v1.0.0-alpha1`).** This is the first public prerelease and has not gone through formal QA.

RAOfflineProxy runs a tiny local proxy on your device. It sits between RetroArch and the RetroAchievements server, saving game and achievement data for offline use and queuing any achievements you unlock while offline. When you reconnect, queued awards are automatically sent to RetroAchievements.

> **Hardcore mode is not supported.** The app only works with softcore achievements.

## Features

- **Play offline** — game data, achievement lists, and unlock history are cached locally so everything works without Wi-Fi or mobile data
- **Automatic award sync** — achievements earned offline are queued and submitted to RetroAchievements as soon as you go online
- **Offline timestamps** — each queued award records the actual time you earned it, so your RA profile shows the real unlock time
- **Easy setup** — starting the proxy automatically patches RetroArch's config; stopping it reverts the change. If hardcore mode was enabled, it is restored on revert
- **Scoped storage friendly** — uses SAF/folder access when direct config writes are not available; no all-files storage permission required
- **ROM scanning** — scan a folder or add individual ROMs to pre-cache everything you need before going offline
- **Auto-start on boot** — optionally starts in the background when your device boots
- **Background refresh** — cached data is refreshed hourly while you are online; stale entries older than 7 days are cleaned up automatically
- **Anti-tamper protection** — queued awards are cryptographically signed and chained on your device, and signatures are verified before awards are sent

## Requirements

- Android **8.0** or newer
- RetroArch installed (any variant)
- A RetroAchievements account with your username and API token configured in RetroArch

## Quick Start

1. Install RAOfflineProxy and open it
2. Start the proxy using the button in the action bar — this automatically patches RetroArch's config and re-checks your RA credentials
3. Go to **Cached Games** and scan your ROM folder or add individual ROMs while online
4. You are ready to play offline

## Important Shutdown Behavior

- On some devices, you should stop sync before killing the app.
- If the app is killed or crashes while the proxy/sync is active, reopen RAOfflineProxy once so it can clean up `retroarch.cfg`.
- Swiping the app away is not a reliable substitute for pressing **Stop proxy** on all devices.

For detailed setup instructions, see the [documentation](https://d3bywedspcdj3v.cloudfront.net/installation.html).

## Build from Source

**Requirements**: Android Studio with Android SDK API 34 and JDK 21 (bundled with Android Studio).

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleDebug
```

## Documentation

Full documentation is available at [d3bywedspcdj3v.cloudfront.net](https://d3bywedspcdj3v.cloudfront.net).

## License

Released under the [GNU GENERAL PUBLIC License](LICENSE).

---

RAOfflineProxy is approved by [RetroAchievements.org](https://retroachievements.org).
