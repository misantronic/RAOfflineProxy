# Installation & Setup (Linux / KNULLI)

> You are installing the current alpha build: `v1.0.0-alpha2`.

## Prerequisites

These setup steps are for KNULLI.

Before using RAOfflineProxy on KNULLI, enter your RetroAchievements account details in the settings.

::: warning RetroArch config is patched automatically
RAOfflineProxy patches the RetroArch config it needs in order to redirect RetroAchievements traffic through the local proxy. For emulator-specific details, see [Emulator CFG Patching](./linux-cfg-patching).
:::

## Step 1: Install RAOfflineProxy

Download `RAOfflineProxy-Knulli-v1.0.0-alpha2-Install.sh` from [GitHub Releases](https://github.com/misantronic/RAOfflineProxy/releases).

Copy it to the KNULLI device:

`/userdata/roms/tools`

Then refresh or update gamelists in EmulationStation so **RAOfflineProxy Install** appears in the **Tools** menu.

Launch **RAOfflineProxy Install** from **Tools**.

After installation, refresh or update gamelists again so the main **RAOfflineProxy** entry appears.

## Step 2: Start the Proxy

After installation, launch **RAOfflineProxy** from the **Tools** menu.

Start the proxy from the on-device menu. RAOfflineProxy will import your saved RetroAchievements login automatically.

## Step 3: Cache Your Games

Once the proxy has been started and your credentials have been imported, go online to cache games for offline use.

You can cache games in either of these ways:

1. **Cache them manually from the RAOfflineProxy menu**
   - Open **Cached Games**
   - Use **Add ROM** to add games to the cache

2. **Cache them automatically by launching them in RetroArch**
   - Keep the proxy running while you are online
   - Open the game once in RetroArch
   - RAOfflineProxy will save the game data in the background

The app will identify each ROM, look it up on RetroAchievements, and save all the achievement data for that game.

See [Caching Games](./linux-caching-games) for full details.

## Step 4: Play Offline

1. Start the proxy from the RAOfflineProxy menu
2. Launch RetroArch and load a cached game
3. Earn achievements - they will queue locally when offline
4. When you reconnect, the proxy automatically sends queued awards to RA

## Quick-start Summary

```
1. Copy the installer to /userdata/roms/tools
2. Run RAOfflineProxy Install from EmulationStation Tools
3. Enter RA credentials in RetroArch
4. Open RAOfflineProxy and start the proxy while online
5. Cache your games from Cached Games or just start them while online to cache them automatically
6. Play!
```
