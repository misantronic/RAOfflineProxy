# Caching Games (Linux)

Before going offline you must save game and achievement data for each game you intend to play.

On Linux, you can do that in two ways:

1. **Automatically** by starting the game in RetroArch while the proxy is running and you are online
2. **Manually** by using **Add ROM** from the RAOfflineProxy menu on supported targets such as KNULLI

## What gets cached

For each game, RAOfflineProxy saves three types of data locally:

| Data | Contents |
|---|---|
| **Game data** | Achievement list, game title, icon, descriptions, and point values |
| **Unlocks** | Which achievements you have already unlocked (softcore only) |
| **Session data** | Built from your cached unlocks and used for offline startup |

::: info Session data is built locally
RAOfflineProxy does not keep live upstream `startsession` responses. Instead, it builds the offline session response from your saved unlock data.
:::

## Caching methods

### Launch the game while online

This is the simplest method.

1. Start the proxy
2. Stay online
3. Launch the game once in RetroArch

RAOfflineProxy will cache the game data in the background.

### Add ROM manually

On KNULLI, the RAOfflineProxy menu includes **Add ROM** in the **Cached Games** flow.

That flow:

1. Opens a controller-driven file browser
2. Identifies the selected ROM
3. Looks up the game on RetroAchievements
4. Saves game data, unlock data, and local session data

## Current manual hashing support

Linux manual ROM adding currently supports these families:

- Game Boy / Game Boy Color / Game Boy Advance
- NES / FDS / SNES
- PC Engine
- Atari 7800 / Atari Lynx
- Super Cassette Vision
- Nintendo 64
- Nintendo DS
- PSP
- PSX

If a system is not listed above, manual ROM adding may not work yet even though normal online game launch caching through RetroArch still works.

## Cached Games on KNULLI

The KNULLI menu currently supports:

- `Add ROM`
- viewing cached games
- removing a selected cached game's cache entries
- clearing game-related cache entries
- previewing cached game images when available
- previewing unlocked achievement badges when available

## Clearing cache

On Linux, clearing cache removes game-related cache entries while preserving cached login and User-Agent data.

On KNULLI, this is available from the RAOfflineProxy menu.
