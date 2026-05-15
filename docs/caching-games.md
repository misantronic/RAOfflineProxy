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

| Data | Contents                                                              |
|---|-----------------------------------------------------------------------|
| **Game data** | Achievement list, game title, icon, descriptions, and point values    |
| **Unlocks** | Which achievements you have already unlocked (softcore only)          |
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

If Smart Cache does not find anything new, it simply finishes without adding more games.

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

## Current Hashing Support

Manual caching does not use one universal hashing rule for every system. Some systems need header stripping, byte-order normalization, or disc-image parsing before the hash is sent to RetroAchievements.

The table below reflects the current app behavior.

| System | Hashing method | Current status |
|---|---|---|
| **NES** | Ignores the 16-byte iNES header when present, then MD5s the remaining ROM data | **✅ Working** |
| **SNES** | Ignores a 512-byte copier header when the file layout matches that format, then MD5s the ROM | **✅ Working** |
| **Atari 2600**<br>**Game Boy**<br>**Game Boy Color**<br>**Game Boy Advance**<br>**Sega Master System**<br>**Sega Mega Drive / Genesis**<br>**Game Gear**<br>**SG-1000**<br>**Sega 32X**<br>**ColecoVision**<br>**Intellivision**<br>**Neo Geo Pocket**<br>**Pokemon Mini**<br>**Virtual Boy**<br>**WonderSwan** | Plain whole-file MD5 | **✅ Working** |
| **Nintendo 64** | Normalizes ROM byte order to `.z64` format first, then MD5s up to the first 64 MiB | **✅ Working** |
| **Nintendo DS** | Hashes the DS header, ARM9 code, ARM7 code, and icon/title block, while ignoring a 512-byte SuperCard header when present | **✅ Working** |
| **PlayStation** | Parses the disc image, reads `SYSTEM.CNF`, finds the boot executable, and hashes the executable path plus executable contents | **✅ Working for `.bin` images**. `.iso` is implemented but not manually tested |
| **PSP** | Parses the ISO and hashes `PSP_GAME\PARAM.SFO` followed by `PSP_GAME\SYSDIR\EBOOT.BIN` | **✅ Working** |
| **GameCube** | Parses the disc image, hashes the disc header plus the `main.dol` sections the same way RetroAchievements expects for GameCube disc images, including Dolphin `.rvz` containers | **✅ Working for `.iso`, `.gcm`, and `.rvz` images** |
| **Wii** | Supports Wii disc-image hashing for `.iso` and `.rvz` images, plus WiiWare hashing for `.wad` packages | **✅ Working for `.iso`, `.rvz`, and `.wad`** |
| **Atari 7800** | Ignores the 128-byte A78 header when present, then MD5s the remaining ROM data | **Best effort only** |
| **Atari Lynx** | Ignores the 64-byte LNX header when present, then MD5s the remaining ROM data | **Best effort only** |
| **PC Engine**<br>**TurboGrafx-16**<br>**SuperGrafx** | Ignores a 512-byte header when the file size indicates one, then MD5s the ROM | **Best effort only** |
| **Super Cassette Vision** | Ignores the 32-byte EmuSCV header when present, then MD5s the remaining ROM data | **Best effort only** |
| **Other formats** | Falls back to plain whole-file MD5 | **Best effort only**. This may or may not match RetroAchievements depending on the system |
| **Dreamcast**<br>**Sega CD**<br>**Saturn**<br>**3DO**<br>**Neo Geo CD**<br>**PC Engine CD**<br>**PC-FX**<br>**Jaguar CD**<br>**Nintendo 3DS**<br>**MS-DOS**<br>**Arcade** | No supported manual hashing path at the moment | **Not manually working** |

::: warning Manual caching support is still format-dependent
If a file format needs custom RetroAchievements hashing and that format is not explicitly listed above, manual caching may skip it even though launching the same game through a supported emulator works.
:::

::: tip A skipped ROM is not always a bug
If the app computes a valid hash but RetroAchievements returns `GameID=0`, the file will still be skipped. That can mean the dump, region, revision, or container variant is not recognized by RA for manual lookup.
:::

If your system or file format is missing from this list, or if your results differ from the current status above, please use the [contact page](./contact).

## Viewing Cached Games

The **Cached Games** screen shows a list of all games currently saved. For each game you can see:

- Game title and icon
- Number of unlocked achievements out of total
- Date last cached
- Cached games counter (`X/100`) while the proxy is running

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

In **Settings** → **Clear Cache** removes all saved game data from the database. Your login credentials are preserved - clearing the cache does not log you out.
