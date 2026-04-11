# Caching Games

Before going offline you must save game and achievement data for each game you intend to play. The **Cached Games** screen handles this.

## What Gets Cached

For each game, the proxy saves three types of data locally:

| Data | Contents |
|---|---|
| **Game data** | Achievement list, game title, icon, descriptions, and point values |
| **Unlocks** | Which achievements you have already unlocked (softcore only) |
| **Session data** | Built from your cached unlocks — used to start a game session offline |

::: info Session data is built locally
The proxy never contacts RA's session endpoint. Instead, it builds the session response from your saved unlock data to avoid unnecessary server calls.
:::

## Caching Methods

### Scan ROM Folder

1. Make sure the proxy is running and you are online
2. Navigate to **Cached Games** → tap the **folder icon** (Scan ROM folder)
3. Pick the folder containing your ROM files
4. The app scans all ROM files, identifies them, and saves their achievement data

Progress is shown in a snackbar at the bottom of the screen.

::: tip
ROMs not recognized by RetroAchievements are skipped. Text files and hidden files are also skipped.
:::

### Add Individual ROM(s)

1. Navigate to **Cached Games** → tap the **plus icon** (Add ROM)
2. Pick one or more ROM files from the file picker
3. Each selected file is identified and its game data is saved

This is useful when you just want to cache one or two games without scanning an entire folder.

## The Caching Process

For each ROM file the following steps happen:

1. **Identify the ROM** — the file is read and a unique fingerprint (hash) is computed
2. **Look up the game** — the hash is sent to RetroAchievements to find the matching game
3. **Save game data** — the full achievement list and game metadata are downloaded and saved
4. **Save unlocks** — your current unlock progress for that game is downloaded and saved
5. **Build session data** — a local session response is built from your saved unlocks (no server call)

There is a short delay between files to avoid overloading the RA servers.

## Viewing Cached Games

The **Cached Games** screen shows a list of all games currently saved. For each game you can see:

- Game title and icon
- Number of unlocked achievements out of total
- Date last cached

## Refreshing Cache

Tap the **refresh icon** to re-fetch data for all cached games while online. This updates achievement lists and your unlock counts. The proxy also runs an **automatic background refresh every 60 minutes** while the service is running and you are online.

## Cache Expiration

Saved game data older than **7 days** is automatically removed during each background refresh cycle. If you plan to go offline for longer than a week, refresh your cache manually before disconnecting. Your login credentials are not affected by cache expiration.

## Deleting a Cached Game

Tap the **trash icon** next to a game to remove it from the cache.

::: warning Known limitation
Deleting a game removes its main game data but leaves behind some related entries (unlock and session data). They will not cause errors but will consume a small amount of storage until the database is cleared.
:::

## Clearing All Cache

In **Settings** → **Clear Cache** removes all saved game data from the database. Your login credentials are preserved — clearing the cache does not log you out.
