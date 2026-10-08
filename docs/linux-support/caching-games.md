# Caching Games

Before going offline, cache each game you want to play. On every Linux target, launching a game once while the proxy is running and you are online will cache that game automatically.

## Flow

On Linux, you can cache games in several ways:

1. Automatically by launching the game in RetroArch while the proxy is active and you are online
2. By using Smart Cache when recent RetroArch history is available
3. Manually from the `Cached games` flow by adding ROMs or caching a whole folder

The Linux menu now includes a ROM browser that can:

1. Add a single ROM to the cache
2. Cache all ROMs in the current folder

Smart Cache can also prefill recent games without browsing manually.

Caching a folder again is quick: games that are already cached are skipped, and ROMs that RetroAchievements did not recognize are not looked up again for 7 days.

## Caching Pace

There is no limit on how many games you can cache.

To go easy on the RetroAchievements servers, adding ROMs, caching a folder, and Smart Cache cache up to **100 new games every 30 minutes**. Anything beyond that is queued and cached in the background during the next 30-minute windows, as long as the proxy is running and you are online. The queue is kept across restarts of the proxy and the device, and it waits while you are playing.

The games are spread over the 30 minutes, about one every 18 seconds, so a full batch of 100 takes the whole window. A single ROM you cache by hand is cached right away.

## Refresh and Expiration

While the proxy is running and online, games you **played in the last 7 days** are refreshed in the background **every 60 minutes**. The refresh waits until the proxy has been idle for 5 minutes, so it does not run while you are playing.

Cached games do not expire. They stay available offline until you remove them or clear the cache. If you unlocked achievements on another device, launch the game once while online before you disconnect so its unlocks are current.
