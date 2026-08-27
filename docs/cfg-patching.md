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

== Other Emulators

**ARMSX1**, **ARMSX2**, **Flycast**, **WatermelonDS**, **Mupen64Plus AE**, and **EmuCoreX** all expose a RetroAchievements host-override broadcast receiver. To redirect achievement traffic to the local proxy, RAOfflineProxy sends a targeted broadcast to the installed package instead of editing a config file:

- The RetroAchievements host override is set to the proxy on your device
- The emulator writes the change to its own configuration and applies it on the next game load
- No config file patching or SAF grant is required
- Emulators that ship under more than one package ID (current, legacy, and debug builds) use the same broadcast flow
- Hardcore mode is left to the emulator, see [Why Hardcore Mode is Disabled](#why-hardcore-mode-is-disabled)

This requires an emulator build that actually ships the receiver. See [Platforms](/platforms.html) for the minimum version per emulator.

:::

Each supported emulator is patched and reverted independently depending on which emulator toggles are enabled in the app.

[Argosy Launcher](https://github.com/rommapp/argosy-launcher) is the exception: it has its own proxy URL setting, so RAOfflineProxy does not patch it at all.

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

== Other Emulators

For these emulators, the app sends a package-targeted broadcast to set the RetroAchievements host override.

- It resolves the installed package from the known package IDs for that emulator, including legacy and debug variants such as `come.nanodata.armsx2` for ARMSX2
- Before sending, it checks that the installed build declares the host-override receiver, and reports a patch error when it does not
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

== Other Emulators

The app sends a package-targeted broadcast to clear the RetroAchievements host override so the emulator connects directly to RetroAchievements again.

:::

## Why Hardcore Mode is Disabled

**Hardcore mode is not supported** by RAOfflineProxy. Any hardcore award request is rejected by the proxy, so leaving hardcore enabled while the proxy is running would result in silent unlock failures.

Which side turns it off depends on how the emulator is patched:

- **RetroArch**, **Dolphin**, and **PPSSPP** when its config file is patched: RAOfflineProxy disables hardcore while patching, records the original value so it survives a process restart, and restores it when you stop the proxy
- **PPSSPP** on the broadcast path, **Flycast**, and **WatermelonDS**: the emulator disables hardcore itself while the custom host is active and restores your setting once the override is cleared, so RAOfflineProxy never writes the setting
- **ARMSX2**: hardcore is deliberately left under your own control, so turn it off in the emulator before starting the proxy
- **ARMSX1** and **Mupen64Plus AE (fork)**: have no hardcore mode at all, so there is nothing to disable. ARMSX1 is softcore-only by construction: its achievements client is hardwired to softcore and the hardcore toggle was removed from its UI

If you are unsure how your emulator behaves, turn hardcore off in the emulator yourself before starting the proxy.
