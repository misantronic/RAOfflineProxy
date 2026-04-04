# Caching Games

Before going offline you must cache game and achievement data for each game you intend to play. The **Cached Games** screen handles this.

## What Gets Cached

For each game, the proxy caches three types of data in the local Room database (`api_cache` table):

| Cache entry | Key pattern | Contents |
|---|---|---|
| **Patch data** | `patch:<gameId>:<user>` | Achievement list, game metadata (title, icon, descriptions, points) |
| **Unlocks** | `unlocks:<gameId>:<user>:0` | Which achievements you have already unlocked (softcore) |
| **Start session** | `startsession:<gameId>:<user>:0` | Synthesized session data built from cached unlocks |

::: info Start Session is synthesized
The live `startsession` endpoint is never called. Instead, the proxy builds the response from your cached unlock data to avoid unnecessary API calls.
:::

## Caching Methods

### Scan ROM Folder

1. Make sure the proxy is running and you are online
2. Navigate to **Cached Games** → tap the **folder icon** (Scan ROM folder)
3. Pick the folder containing your ROM files
4. The app scans all ROM files, computing their MD5 hashes and looking up their Game IDs on RA

Progress is shown in a snackbar at the bottom of the screen.

::: tip
All ROMs not recognized by RetroAchievements are skipped. Files with a `.txt` extension and hidden files (starting with `.`) are also skipped.
:::

### Add Individual ROM(s)

1. Navigate to **Cached Games** → tap the **plus icon** (Add ROM)
2. Pick one or more ROM files from the file picker
3. Each selected file is hashed and its game data is cached

This is useful when you just want to cache one or two games without scanning an entire folder.

## The Caching Process

For each ROM file the following steps happen:

1. **MD5 hash** — the ROM file is read and its MD5 digest is computed
2. **Game ID lookup** — `GET /dorequest.php?r=gameid&m=<hash>` is sent **via the local proxy** (so the gameid response is also cached)
3. **Patch fetch** — `GET /dorequest.php?r=patch&g=<gameId>` is sent via the proxy (cached as `patch:gameId:user`)
4. **Unlocks fetch** — `GET /dorequest.php?r=unlocks&g=<gameId>&h=0` is sent via the proxy (cached as `unlocks:gameId:user:0`)
5. **Start session synthesis** — a fake `startsession` JSON is built from the cached unlock list and stored directly in the DB (no network call)

There is a 500 ms delay between files to avoid hammering the RA servers.

## Viewing Cached Games

The **Cached Games** screen shows a list of all games currently in the cache. For each game you can see:

- Game title and icon
- Number of unlocked achievements out of total
- Date last cached

## Refreshing Cache

Tap the **refresh icon** to re-fetch data for all cached games while online. This updates achievement lists and your unlock counts. The proxy also runs an **automatic background refresh every 60 minutes** while the service is running and you are online.

## Deleting a Cached Game

Tap the **trash icon** next to a game to remove it from the cache.

::: warning Known limitation
Deleting a game only removes the `patch:*` cache entries. The corresponding `unlocks:*` and `startsession:*` entries for that game are left behind. They will not cause errors but will consume a small amount of storage until the database is cleared.
:::

## Clearing All Cache

In **Settings** → **Clear Cache** removes all `patch:*`, `gameid:*`, `unlocks:*`, and `startsession:*` entries from the database. Login credentials (`login2:*`) and the last user-agent (`ua::last`) are preserved.
