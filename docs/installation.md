# Installation & Setup

> You are installing the current alpha build: `v1.0.0-alpha1`.

## Step 1: Install RAOfflineProxy

Install the APK on your Android device. You can download the latest alpha prerelease from [GitHub Releases](https://github.com/misantronic/RAOfflineProxy/releases) or build from source.

::: tip Minimum Android version
Android **8.0** or newer is required.
:::

## Step 2: Enter Your RetroAchievements Credentials in RetroArch

Before using RAOfflineProxy, enter your RetroAchievements account details in RetroArch.

In RetroArch:
1. Open **Settings → Achievements**
2. Enter your RetroAchievements **Username** and **Password** (or API Token)
3. Save the settings

## Step 3: Start the Proxy and Complete the First Online Game Launch

Starting the proxy automatically patches RetroArch's config file to redirect achievement traffic to the local proxy. If direct file access is unavailable, the app asks you to grant folder access.

1. Make sure you are **online**
2. Start the proxy
3. Start any game in RetroArch
4. Wait for RetroArch to log in successfully to RetroAchievements

After that first successful online login through the proxy, the setup is ready.

::: warning Shutdown caveat
On some devices, swiping the app away or crashing while the proxy is active does not reliably revert `retroarch.cfg` immediately. Stop sync before killing the app. If that happens, reopen RAOfflineProxy once so it can clean up `retroarch.cfg`.
:::

## Step 4: Cache Your Games

Once the proxy has been started and RetroArch has logged in successfully through it, you can cache games for offline use.

1. Navigate to **Cached Games** in the drawer
2. Choose one of:
   - **Scan ROM folder** — picks a folder and scans all ROMs in it
   - **Add ROM** — picks individual ROM file(s)

The app will identify each ROM, look it up on RetroAchievements, and save all the achievement data for that game.

Manual caching is capped at **50 games** to limit bulk server requests to RetroAchievements. The **Cached Games** screen shows the current total as `X/50 cached` while the proxy is running.

::: tip Automatic caching when launching games
Caching also happens automatically when you open a game in RetroArch while the proxy is running and you are online — the proxy saves the game data in the background. Manual scanning is only needed if you want to pre-cache games before going offline without launching them first.
:::

See [Caching Games](./caching-games) for full details.

## Step 5: Play Offline

1. Start the proxy service (press **Start proxy** in the action bar, or enable auto-start)
2. Launch RetroArch and load a cached game
3. Earn achievements — they will queue locally when offline
4. When you reconnect, the proxy automatically sends queued awards to RA

::: tip Test with the SNES Burn-in Test Cartridge
The [SNES Burn-in Test Cartridge Test Kit](https://retroachievements.org/game/10701) is the perfect game to verify everything works. The ["I Can Move!"](https://retroachievements.org/achievement/52113) achievement is very easy to earn, and you can reset it on the achievement details page to test again. Great for confirming that offline queuing and syncing work correctly.
:::

## Quick-start Summary

```
1. Install APK
2. Enter RA credentials in RetroArch
3. Open RAOfflineProxy → Start proxy → launch a game in RetroArch while online
4. Cache your games from **Cached Games** or just start them while online to cache them automatically
5. Play!
```
