# Caching Games (Android)

Before going offline you must save game and achievement data for each game you intend to play.

You can do that in two ways:

1. **Automatically** by starting the game in a supported emulator while the proxy is running and you are online
2. **Manually** from the **Cached Games** screen by adding ROMs or scanning a ROM folder

The rest of this page explains the manual caching flow in the **Cached Games** screen and what data gets saved locally.

## Cache Limit

Manual caching is limited to **100 cached games** at a time.

This limit exists to keep bulk caching from generating too many RetroAchievements requests at once. Each manually cached game can require multiple upstream requests, so the cap helps reduce server load while still leaving enough room for a practical offline library.

When the proxy is running, the **Cached Games** header shows a counter such as `12/100 cached`. Once you reach `100/100`, the **Scan ROM folder** and **Add ROM** actions are disabled until you delete some cached games or clear the cache.

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

If Smart Cache does not find anything new, it simply finishes without adding more games.

::: warning ARMSX2, ARMSX2 Refresh, and Flycast are not Smart Cache sources
**ARMSX2**, **ARMSX2 Refresh**, and **Flycast** do not expose a recent-games list that the app can read. To cache games from these emulators, use **Scan ROM Folder** or **Add Individual ROM(s)** instead.
:::

### Scan ROM Folder

1. Make sure the proxy is running and you are online
2. Navigate to **Cached Games** → tap the **folder icon** (Scan ROM folder)
3. Pick the folder containing your ROM files
4. The app scans all ROM files, identifies them, and saves their achievement data

If the scan reaches the **100-game cache limit**, it stops there and skips the remaining files.

Progress is shown in a snackbar at the bottom of the screen.

::: tip
ROMs not recognized by RetroAchievements are skipped. Text files and hidden files are also skipped.
:::

### Add Individual ROM(s)

1. Navigate to **Cached Games** → tap the **plus icon** (Add ROM)
2. Pick one or more ROM files from the file picker
3. Each selected file is identified and its game data is saved

This is useful when you just want to cache one or two games without scanning an entire folder.

If adding ROMs would push the cache above **100 games**, the app stops once the limit is reached.

## The Caching Process

For each ROM file the following steps happen:

1. **Identify the ROM**: the file is read and a unique fingerprint (hash) is computed
2. **Look up the game**: the hash is sent to RetroAchievements to find the matching game
3. **Save game data**: the full achievement list and game metadata are downloaded and saved
4. **Save unlocks**: your current unlock progress for that game is downloaded and saved
5. **Build session data**: a local session response is built from your saved unlocks (no server call)

There is a short delay between files to avoid overloading the RA servers.

## Viewing Cached Games

The **Cached Games** screen shows a list of all games currently saved. For each game you can see:

- Game title and icon
- Number of unlocked achievements out of total
- Date last cached
- Cached games counter (`X/100`) while the proxy is running

## Refreshing Cache

Tap the **refresh icon** to re-fetch data for all cached games while online. This updates achievement lists and your unlock counts. The proxy also runs an **automatic background refresh every 60 minutes** while the service is running and you are online.

## Cache Expiration

Saved game data older than **60 days** is automatically removed during each background refresh cycle. If you plan to go offline for longer than a week, refresh your cache manually before disconnecting. Your login credentials are not affected by cache expiration.

## Deleting a Cached Game

Tap the **trash icon** next to a game to remove it from the cache.

## Clearing All Cache

In **Settings** → **Clear Cache** removes all saved game data from the database. Your login credentials are preserved - clearing the cache does not log you out.
