# Installation & Setup

## Step 1 — Install RAOfflineProxy

Install the APK on your Android device. You can download the latest release from [GitHub Releases](https://github.com/misantronic/RAOfflineProxy/releases) or build from source.

::: tip Minimum Android version
Android **8.0 (API 26)** is required.
:::

## Step 2 — Log in to RetroAchievements in RetroArch

Before using RAOfflineProxy you **must** log in to your RetroAchievements account inside RetroArch at least once while online. This stores your credentials (username + API token) in RetroArch's internal storage, which the proxy reads later.

In RetroArch:
1. Open **Settings → Achievements**
2. Enter your RetroAchievements **Username** and **Password** (or API Token)
3. Save and confirm the login succeeds

## Step 3 — Patch retroarch.cfg

RAOfflineProxy needs to redirect RetroArch's achievement traffic to the local proxy. This is done by writing `cheevos_custom_host = "127.0.0.1:8080"` into `retroarch.cfg`.

1. Open RAOfflineProxy
2. Navigate to **RetroArch Setup** in the drawer
3. Tap **Patch retroarch.cfg**

The app will attempt to patch the file using one of four strategies in order:

| Strategy | When used |
|---|---|
| **SAF tree URI** | A folder grant was previously granted via "Grant Folder Access" |
| **Direct file write** | The cfg file exists and is directly writable |
| **SAF grant prompt** | File found but not writable on Android ≤ 12 — app prompts for folder access |
| **Staging copy** | All else fails — copies cfg to `/sdcard/RAOfflineProxy/`, patches there, attempts to copy back |

See [RetroArch CFG Patching](./cfg-patching) for full details and manual fallback instructions.

## Step 4 — Cache Your Games

Before going offline you need to cache game and achievement data for each game you want to play.

1. Make sure you are **online**
2. Start the proxy (press **Start proxy** in the action bar)
3. Navigate to **Cached Games** in the drawer
4. Choose one of:
   - **Scan ROM folder** — picks a folder and scans all ROMs in it
   - **Add ROM** — picks individual ROM file(s)

The app will compute each ROM's MD5 hash, look up its Game ID on RA, then cache the patch data, unlocks, and session data for that game.

::: tip Automatic caching when launching games
Caching also happens automatically when you open a game in RetroArch while the proxy is running and you are online — the proxy intercepts and caches the `patch`, `unlocks`, and `gameid` responses in the background. Manual scanning is only needed if you want to pre-cache games before going offline without launching them first.
:::

See [Caching Games](./caching-games) for full details.

## Step 5 — Play Offline

1. Start the proxy service (press **Start proxy** in the action bar, or enable auto-start)
2. Launch RetroArch and load a cached game
3. Earn achievements — they will queue locally when offline
4. When you reconnect, the proxy automatically flushes queued awards to RA

## Quick-start Summary

```
1. Install APK
2. Log in to RA inside RetroArch (while online)
3. Open RAOfflineProxy → RetroArch Setup → Patch retroarch.cfg
4. Open RAOfflineProxy → Cached Games → scan your ROM folder
5. Start the proxy and play!
```
