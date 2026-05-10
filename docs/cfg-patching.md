# Emulator Config Patching

## Overview

RAOfflineProxy patches the supported emulator config so RetroAchievements traffic is redirected to the local proxy.

### RetroArch

On Android, RetroArch stores its configuration in a file called `retroarch.cfg`. To redirect achievement traffic to the local proxy, RAOfflineProxy changes two settings in this file and imports your saved RetroAchievements login:

- The **custom achievement server** is pointed at the proxy on your device
- **Hardcore mode** is disabled (since it is not supported)
- The saved `cheevos_username` and `cheevos_token` are imported into RAOfflineProxy's local credential cache when present
- If no token is present, `cheevos_username` and `cheevos_password` are used once to retrieve a token through RA's login endpoint

### Dolphin

On Android, Dolphin stores its RetroAchievements configuration in `Config/RetroAchievements.ini`. To redirect achievement traffic to the local proxy, RAOfflineProxy updates the Dolphin achievements config and imports your saved login:

- `HostUrl` is pointed at the proxy on your device
- `HardcoreEnabled` is disabled (since it is not supported)
- The saved `Username` and `ApiToken` are imported into RAOfflineProxy's local credential cache when present

RetroArch and Dolphin are patched and reverted independently depending on which emulator toggles are enabled in the app.

Patching and reverting happen **automatically** when you start and stop the proxy: there is no separate setup step.

## Automatic Patching (Start Proxy)

When you press **Start proxy** in the action bar, the app imports credentials from each enabled supported emulator, patches the emulator config, then starts the proxy service.

### RetroArch

For RetroArch, the app patches `retroarch.cfg`. If the app needs folder access to write the file, it shows a dialog prompting you to grant access to the RetroArch folder that contains `retroarch.cfg`.

Before patching, the app also checks for a one-time sibling backup named `retroarch.raofflineproxy.cfg`. If that file does not already exist, RAOfflineProxy creates it from the current `retroarch.cfg`.

- The backup is created only once
- The app does not overwrite it later
- The app does not restore from it automatically

The app tries four strategies in order:

#### Strategy 1: Folder Access (preferred)

If you have previously granted folder access, the app uses the saved permission to read and write the file directly. No extra prompts.

#### Strategy 2: Direct File Write

The app checks common RetroArch installation paths for a writable `retroarch.cfg`. If the file is found and writable, it is patched in place.

#### Strategy 3: Folder Access Prompt (Android 12 and below)

On Android 12 and below, if the file is found but not directly writable, the app shows a dialog asking you to grant folder access. Tapping **Grant** opens the system folder picker directly at the RetroArch folder that contains `retroarch.cfg` when possible.

Grant access to the suggested folder.

After granting, the app retries automatically. If the selected folder does not contain `retroarch.cfg`, the app shows an error and asks again the next time you start the proxy.

#### Strategy 4: Staging Copy

If all else fails, the app copies `retroarch.cfg` to a temporary folder, patches it there, and attempts to copy it back. If the copy-back fails, you are shown the file path and must manually copy it.

### Dolphin

For Dolphin, the app patches `Config/RetroAchievements.ini`.

- It looks for the Dolphin config in common Android Dolphin paths
- If the file is directly writable, it patches it in place
- If folder access is needed on older Android versions, the app can request access to the Dolphin folder in the same way it does for RetroArch
- Before patching, it creates a one-time sibling backup named `RetroAchievements.raofflineproxy.ini` if that backup does not already exist

## Automatic Reverting (Stop Proxy)

When you press **Stop proxy**, the app reverts any enabled emulator configs that were patched when the proxy started.

### RetroArch

The app reverts `retroarch.cfg` to clear the custom server setting so RetroArch connects directly to RetroAchievements again.

### Dolphin

The app reverts `Config/RetroAchievements.ini` so Dolphin connects directly to RetroAchievements again.

If hardcore mode was enabled before you started the proxy, it is automatically restored when you stop the proxy. The app records the original hardcore setting when patching and saves it so it survives process restarts.

## Important Shutdown Caveat

- Always stop sync before killing the app.
- On some devices, swiping the app away or crashing while the proxy is active does not reliably revert the patched emulator config immediately.
- If the app was killed or crashed during sync, reopen RAOfflineProxy once so it can restore the config on launch.

## Checking Patch Status

The app checks the patch status on startup. The action bar proxy button reflects whether the proxy is running.

## Why Hardcore Mode is Disabled

The patcher disables hardcore mode because **hardcore mode is not supported** by RAOfflineProxy. Any hardcore award request is rejected by the proxy. Keeping hardcore enabled in a supported emulator while using the proxy would result in silent unlock failures.

When you stop the proxy, hardcore mode is restored to its original state: if you had it enabled before, it will be re-enabled automatically.
