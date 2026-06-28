# Emulator Config Patching (Android)

## Overview

RAOfflineProxy patches the supported emulator config so RetroAchievements traffic is redirected to the local proxy.

:::tabs key:android-emulator

== RetroArch

RetroArch stores its configuration in a file called `retroarch.cfg`. To redirect achievement traffic to the local proxy, RAOfflineProxy changes two settings in this file and imports your saved RetroAchievements login:

- The **custom achievement server** is pointed at the proxy on your device
- **Hardcore mode** is disabled
- The saved `cheevos_username` and `cheevos_token` are imported into RAOfflineProxy's local credential cache when present
- If no token is present, `cheevos_username` and `cheevos_password` are used once to retrieve a token through RA's login endpoint

== Dolphin

Dolphin stores its RetroAchievements configuration in `Config/RetroAchievements.ini`. To redirect achievement traffic to the local proxy, RAOfflineProxy updates the Dolphin achievements config and imports your saved login:

- `HostUrl` is pointed at the proxy on your device
- `HardcoreEnabled` is disabled
- The saved `Username` and `ApiToken` are imported into RAOfflineProxy's local credential cache when present

== PPSSPP

PPSSPP supports two patching paths depending on the installed build. RAOfflineProxy either uses PPSSPP's RetroAchievements host-override broadcast support or updates `PSP/SYSTEM/ppsspp.ini` directly:

- `AchievementsHost` is pointed at the proxy on your device
- `AchievementsChallengeMode` is disabled
- The saved `AchievementsUserName` and token from `ppsspp_retroachievements.dat` are imported into RAOfflineProxy's local credential cache when present
- When broadcast override support is available, RAOfflineProxy uses that path instead of editing the config file

== ARMSX2

ARMSX2 exposes a RetroAchievements host-override broadcast receiver. To redirect achievement traffic to the local proxy, RAOfflineProxy sends a targeted broadcast to the installed ARMSX2 package:

- The RetroAchievements host override is set to the proxy on your device
- No config file patching or SAF grant is required for ARMSX2 itself
- RAOfflineProxy uses the same broadcast-only patch and revert flow for all supported ARMSX2 package IDs (legacy ARMSX2 and ARMSX2 Refresh)

:::

RetroArch, Dolphin, PPSSPP, and ARMSX2 are patched and reverted independently depending on which emulator toggles are enabled in the app.

Patching and reverting happen **automatically** when you start and stop the proxy: there is no separate setup step. For the patched settings to be picked up reliably, fully close the emulator before starting or stopping the proxy, then relaunch it afterward.

## Automatic Patching (Start Proxy)

When you press **Start proxy** in the action bar, the app imports credentials from each enabled supported emulator, patches the emulator config, then starts the proxy service. Do this only while the emulator is fully closed.

:::tabs key:android-emulator

== RetroArch

For RetroArch, the app patches `retroarch.cfg`.

- It looks for the RetroArch config in common Android RetroArch paths
- If you have previously granted folder access, it uses the saved permission to patch the file directly
- If the file is directly writable, it patches it in place
- On Android 12 and below, if folder access is needed, the app can prompt you to grant access to the RetroArch folder that contains `retroarch.cfg`
- Before patching, it creates a one-time sibling backup named `retroarch.raofflineproxy.cfg` if that backup does not already exist

== Dolphin

For Dolphin, the app patches `Config/RetroAchievements.ini`.

- It looks for the Dolphin config in common Android Dolphin paths
- If the file is directly writable, it patches it in place
- If folder access is needed on older Android versions, the app can request access to the Dolphin folder in the same way it does for RetroArch
- Before patching, it creates a one-time sibling backup named `RetroAchievements.raofflineproxy.ini` if that backup does not already exist

== PPSSPP

For PPSSPP, the app prefers a package-targeted broadcast override when the installed build exposes it.

- It sends the RetroAchievements host override directly to the installed PPSSPP package when broadcast support is available
- Otherwise, it patches `PSP/SYSTEM/ppsspp.ini` through the granted PPSSPP storage root
- The PPSSPP folder grant must resolve to a tree containing `PSP/SYSTEM/ppsspp.ini`
- The emulator should still be fully closed before patching so the new override or config is picked up cleanly on next launch

== ARMSX2

For ARMSX2, the app sends a package-targeted broadcast to set the RetroAchievements host override.

- It supports `come.nanodata.armsx2` and `com.armsx2` (ARMSX2 Refresh)
- No config file is edited
- No folder access prompt is needed
- The emulator should still be fully closed before patching so the new override is picked up cleanly on next launch

:::

## Automatic Reverting (Stop Proxy)

When you press **Stop proxy**, the app reverts any enabled emulator configs that were patched when the proxy started. Do this only while the emulator is fully closed, then relaunch it after the revert finishes.

:::tabs key:android-emulator

== RetroArch

The app reverts `retroarch.cfg` to clear the custom server setting so RetroArch connects directly to RetroAchievements again.

== Dolphin

The app reverts `Config/RetroAchievements.ini` so Dolphin connects directly to RetroAchievements again.

== PPSSPP

For PPSSPP, the app clears the broadcast host override when available. Otherwise, it reverts `PSP/SYSTEM/ppsspp.ini` so PPSSPP connects directly to RetroAchievements again.

== ARMSX2

The app sends a package-targeted broadcast to clear the RetroAchievements host override so ARMSX2 connects directly to RetroAchievements again.

:::

If hardcore mode was enabled before you started the proxy, it is automatically restored when you stop the proxy. The app records the original hardcore setting when patching and saves it so it survives process restarts.

## Why Hardcore Mode is Disabled

The patcher disables hardcore mode because **hardcore mode is not supported** by RAOfflineProxy. Any hardcore award request is rejected by the proxy. Keeping hardcore enabled in a supported emulator while using the proxy would result in silent unlock failures.

When you stop the proxy, hardcore mode is restored to its original state: if you had it enabled before, it will be re-enabled automatically.
