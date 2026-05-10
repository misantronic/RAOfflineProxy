# Installation & Setup

> You are installing the current alpha build: `v1.0.0-alpha2`.

## Prerequisites

These setup steps are for the Android app.

Before using RAOfflineProxy on Android, enter your RetroAchievements account details in all supported emulators you plan to use.

::: warning Emulator config is patched automatically
RAOfflineProxy patches the supported emulator config it needs in order to redirect RetroAchievements traffic through the local proxy. For emulator-specific details, see [Emulator CFG Patching](./cfg-patching).
:::

## Step 1: Install RAOfflineProxy

Install the APK on your Android device. You can download the latest alpha prerelease from [GitHub Releases](https://github.com/misantronic/RAOfflineProxy/releases) or build from source.

## Step 2: Start the Proxy

Start the proxy. RAOfflineProxy will import your saved RetroAchievements login automatically. On some devices, you may also need to grant folder access.

## Step 3: Cache Your Games

Once the proxy has been started and your credentials have been imported, go online to cache games for offline use.

You can cache games in either of these ways:

1. **Cache them manually from RAOfflineProxy**
   - Navigate to **Cached Games** in the drawer
   - Choose one of:
     - **Scan ROM folder**: picks a folder and scans all ROMs in it
     - **Add ROM**: picks individual ROM file(s)

2. **Cache them automatically by launching them in a supported emulator**
   - Keep the proxy running while you are online
   - Open the game once in RetroArch or Dolphin
   - RAOfflineProxy will save the game data in the background

The app will identify each ROM, look it up on RetroAchievements, and save all the achievement data for that game.

Manual caching is capped at **50 games** to limit bulk server requests to RetroAchievements. The **Cached Games** screen shows the current total as `X/50 cached` while the proxy is running.

See [Caching Games](./caching-games) for full details.

## Step 4: Play Offline

1. Start the proxy service (press **Start proxy** in the action bar, or enable auto-start)
2. Launch RetroArch or Dolphin and load a cached game
3. Earn achievements - they will queue locally when offline
4. When you reconnect, the proxy automatically sends queued awards to RA

::: tip Test with the SNES Burn-in Test Cartridge
The [SNES Burn-in Test Cartridge Test Kit](https://retroachievements.org/game/10701) is the perfect game to verify everything works. The ["I Can Move!"](https://retroachievements.org/achievement/52113) achievement is very easy to earn, and you can reset it on the achievement details page to test again. Great for confirming that offline queuing and syncing work correctly.
:::

## Quick-start Summary

```
1. Install APK
2. Enter RA credentials in RetroArch or Dolphin
3. Open RAOfflineProxy → Start proxy while online
4. Cache your games from **Cached Games** or just start them while online to cache them automatically
5. Play!
```
