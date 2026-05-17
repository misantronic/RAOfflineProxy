# Caching Games

Before going offline, cache each game you want to play. On every Linux target, launching a game once while the proxy is running and you are online will cache that game automatically.

## Flow

:::tabs key:linux-target

== KNULLI

On KNULLI, you can cache games in two ways:

1. Automatically by launching the game in RetroArch while the proxy is active and you are online
2. Manually by using **Add ROM** from the KNULLI **Cached Games** menu

KNULLI has the most complete manual caching flow on Linux right now.

The menu includes a controller-driven ROM browser that:

1. Identifies the selected ROM
2. Looks it up on RetroAchievements
3. Caches game data locally

Current manual hashing coverage includes:

- Game Boy / Game Boy Color / Game Boy Advance
- NES / FDS / SNES
- PC Engine
- Atari 7800 / Atari Lynx
- Super Cassette Vision
- Nintendo 64
- Nintendo DS
- PSP
- PSX

== Onion

On Onion, the practical way to cache a game is:

1. Start the proxy
2. Stay online
3. Launch the game once in RetroArch

That caches the per-game data needed for offline use.

What is currently working:

- Per-game `achievementsets` caching
- Cached real `startsession` reuse for offline unlocked-count display
- Cached-games listing from the Onion app menu

:::

## What Gets Cached

For each cached game, `RAOfflineProxy` stores enough information to let RetroArch start the game offline and keep track of your softcore progress.

That includes:

- Game data and achievement definitions
- Unlock data for softcore achievements
- Local session data used for offline startup

## Cached Games Menu

:::tabs key:linux-target

== KNULLI

The KNULLI cached-games area currently supports:

- `Add ROM`
- Viewing cached games
- Removing a selected cached game's cache entries
- Clearing game-related cache entries
- Previewing cached game images
- Previewing unlocked achievement badges when available

Clearing cache removes game-related cache entries while preserving cached login and User-Agent data.

== Onion

The Onion app currently includes a `Cached games` menu entry that lists cached games known to the local cache.

Current limitations:

- No `Add ROM`
- No cached-game removal UI yet
- No image-preview UI yet

:::
