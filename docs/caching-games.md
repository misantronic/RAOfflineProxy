# Caching Games (Android)

Before going offline you must save game and achievement data for each game you intend to play.

You can do that in two ways:

1. **Automatically** by starting the game in a supported emulator while the proxy is running and you are online
2. **Manually** from the **Cached Games** screen by adding ROMs or scanning a ROM folder

The rest of this page explains the manual caching flow in the **Cached Games** screen and what data gets saved locally.

## Caching Pace

There is no limit on how many games you can cache.

To go easy on the RetroAchievements servers, the **Add ROM**, **Scan ROM folder**, and **Smart Cache** actions cache up to **100 new games every 30 minutes**. Anything beyond that is queued and cached in the background during the next 30-minute windows, as long as the proxy is running and you are online. The queue is kept across restarts of the app and the device, and it waits while you are playing.

## What Gets Cached

For each game, the proxy saves three types of data locally:

| Data             | Contents                                                              |
| ---------------- | --------------------------------------------------------------------- |
| **Game data**    | Achievement list, game title, icon, descriptions, and point values    |
| **Unlocks**      | Which achievements you have already unlocked (casual only)          |
| **Session data** | Built from your cached unlocks - used to start a game session offline |

::: info Session data is built locally
The proxy never contacts RA's session endpoint. Instead, it builds the session response from your saved unlock data to avoid unnecessary server calls.
:::

## Caching Methods

### Smart Cache

Smart Cache is a shortcut for quickly adding games you have played recently.

When it runs, the app looks at recent game activity from supported emulators, tries to match those games to ROM files you can read, and then caches the ones it recognizes.

This is useful when you do not want to scan an entire ROM folder but still want your most recently played games ready for offline use.

Smart Cache can use recent activity from:

- **RetroArch** recent history
- **Dolphin** recent GameCube and Wii save data
- **PPSSPP** recent games list
- **ARMSX1** (0.1.5 or newer) and **ARMSX2** (2.6.7 or newer) recently played list

If Smart Cache does not find anything new, it simply finishes without adding more games.

::: info ARMSX1 and ARMSX2 ask for permission once
The first time Smart Cache reads from ARMSX1 or ARMSX2, the emulator asks whether RAOfflineProxy may access its recently played games. Tap **Allow**. If you decline, Smart Cache skips that emulator until you turn sharing back on in the emulator's app settings.
:::

::: warning Flycast is not a Smart Cache source
**Flycast** does not expose a recent-games list that the app can read. To cache Flycast games, use **Scan ROM Folder** or **Add Individual ROM(s)** instead.
:::

### Scan ROM Folder

1. Make sure the proxy is running and you are online
2. Navigate to **Cached Games** → tap the **folder icon** (Scan ROM folder)
3. Pick the folder containing your ROM files
4. The app scans all ROM files, identifies them, and saves their achievement data

If the folder contains more new games than can be cached right now, the rest is queued and cached in the background (see [Caching Pace](#caching-pace)).

Progress is shown in a snackbar at the bottom of the screen, first as `Hashing x/y` while the ROMs are identified, then as `Caching x/y` while the first batch is saved. When it finishes, the snackbar sums up how many games were cached, queued, and skipped.

If you abort a scan, the games it queued are removed from the queue again. Games it already cached stay cached.

::: tip
ROMs not recognized by RetroAchievements are skipped. Text files and hidden files are also skipped.

Scanning the same folder again is quick: games that are already cached are skipped, and ROMs that RetroAchievements did not recognize are not looked up again for 7 days.
:::

### Add Individual ROM(s)

1. Navigate to **Cached Games** → tap the **plus icon** (Add ROM)
2. Pick one or more ROM files from the file picker
3. Each selected file is identified and its game data is saved

This is useful when you just want to cache one or two games without scanning an entire folder.

If you pick more new games than can be cached right now, the rest is queued and cached in the background (see [Caching Pace](#caching-pace)).

## The Caching Process

Caching runs in two phases:

1. **Identify the ROMs**: every file is read and a unique fingerprint (hash) is computed. This happens on your device only; ROMs that are already cached, or that RetroAchievements recently did not recognize, are skipped here. Everything else is queued.
2. **Cache the queued games**: games are taken from the queue in batches of up to 100 every 30 minutes (see [Caching Pace](#caching-pace)). The first batch starts as soon as all ROMs are identified. For each game:
   - **Look up the game**: the hash is sent to RetroAchievements to find the matching game. If the same ROM was looked up before, the saved answer is used instead
   - **Save game data**: the full achievement list and game metadata are downloaded and saved
   - **Save unlocks**: your current unlock progress for that game is downloaded and saved
   - **Build session data**: a local session response is built from your saved unlocks (no server call)

After every 50 games there is a short pause to avoid overloading the RA servers.

## Viewing Cached Games

The **Cached Games** screen shows a list of all games currently saved. For each game you can see:

- Game title and icon
- Number of unlocked achievements out of total
- Date last cached
- Cached games counter, plus the number of queued games while any are waiting

## Refreshing Cache

Cached games are kept up to date automatically:

- **Launching a game while online** fetches its latest achievements and unlocks.
- The proxy runs an **automatic background refresh every 60 minutes** while the service is running and you are online. To keep the load on the RetroAchievements servers low, it only covers games you **played in the last 7 days**, and it waits until the proxy has been idle for 5 minutes, so it does not run while you are playing.

::: tip Before going offline with a game you have not played lately
A game you have not launched recently keeps the data from when it was cached. If you unlocked achievements on another device, launch the game once while online before you disconnect, so your offline unlock state is current.
:::

## Cache Expiration

Cached games do not expire. They stay available offline until you delete them or clear the cache, no matter how long you are offline. Only temporary data the proxy saves along the way is removed after **60 days**. Your login credentials are never removed.

## Deleting a Cached Game

Tap the **trash icon** next to a game to remove it from the cache.

## Clearing All Cache

In **Settings** → **Clear Cache** removes all saved game data from the database. Your login credentials are preserved - clearing the cache does not log you out.
