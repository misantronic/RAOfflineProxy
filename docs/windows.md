# Windows Setup

::: warning Experimental
Windows support is experimental. It is new and has had little testing, so expect rough edges.
:::

RAOfflineProxy for Windows is a **portable folder**: nothing is installed, and settings and cached games stay in the folder. It works with RetroArch, Dolphin, PCSX2 and PPSSPP (PPSSPP is untested).

## Setup

1. Download `RAOfflineProxy-Windows-*.zip` from [GitHub Releases](https://github.com/misantronic/RAOfflineProxy/releases)
2. Unzip it anywhere, for example a folder or a USB stick
3. Enter your RetroAchievements account details in your emulator
4. Double-click **Start RAOfflineProxy.cmd**. Windows may warn the first time: choose **More info**, then **Run anyway**
5. Start the proxy while online. The first start finds your emulators
6. Add your ROMs: open **Cached games > Add ROM** and pick either the top-level folder that holds all your system folders, or a single system's folder
7. Check that the folder is listed under **Watched folders**. New games added to it are cached automatically
8. Cache games with **Smart Cache** or by launching them once in your emulator
9. Optional: turn on **Settings > Start with Windows** to start the proxy when you sign in. It asks for admin once, and also starts in the full screen experience
10. Optional: on the **Emulators** screen, turn off any emulator you want the proxy to leave alone

The proxy runs in the background with a tray icon. The menu works with a controller or the keyboard.

## Features

- Supports RetroArch, Dolphin, PCSX2 and PPSSPP
- Works in the Windows **full screen experience** (Xbox mode on handhelds)
- **Settings > Add to Steam** adds RAOfflineProxy to Steam as a non-Steam game, so you can open it from Steam or Big Picture
- **Portable**: runs from a USB stick, even when its drive letter changes. A folder copied to another PC finds that PC's emulators and keeps its cache
- **Crash-safe**: after a crash or power cut, your emulator configs are put back at the next sign-in, so they never point at a proxy that isn't running
- **Awards history** lists your offline unlocks and whether each has been sent
- **Settings > Smart Cache on proxy start** caches your recently played games every time the proxy starts

## Offline use

Launch a cached game and play. Unlocks are queued and sent when you are back online.

## Stop and remove

- **Stop RAOfflineProxy.cmd** stops the proxy and restores your emulator configs.
- To remove it: run Stop, choose **Settings > Remove from Windows sign-in**, then delete the folder.

Updates install from the menu when a new version is out. Hardcore mode is not supported.
