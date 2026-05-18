# Emulator Config Patching (Android)

## Overview

RAOfflineProxy patches the supported emulator config so RetroAchievements traffic is redirected to the local proxy.

:::tabs key:android-emulator

== RetroArch

RetroArch stores its configuration in a file called `retroarch.cfg`. To redirect achievement traffic to the local proxy, RAOfflineProxy changes two settings in this file and imports your saved RetroAchievements login:

- The **custom achievement server** is pointed at the proxy on your device
- **Hardcore mode** is disabled (since it is not supported)
- The saved `cheevos_username` and `cheevos_token` are imported into RAOfflineProxy's local credential cache when present
- If no token is present, `cheevos_username` and `cheevos_password` are used once to retrieve a token through RA's login endpoint

== Dolphin

Dolphin stores its RetroAchievements configuration in `Config/RetroAchievements.ini`. To redirect achievement traffic to the local proxy, RAOfflineProxy updates the Dolphin achievements config and imports your saved login:

- `HostUrl` is pointed at the proxy on your device
- `HardcoreEnabled` is disabled (since it is not supported)
- The saved `Username` and `ApiToken` are imported into RAOfflineProxy's local credential cache when present

:::

RetroArch and Dolphin are patched and reverted independently depending on which emulator toggles are enabled in the app.

Patching and reverting happen **automatically** when you start and stop the proxy: there is no separate setup step.

## Automatic Patching (Start Proxy)

When you press **Start proxy** in the action bar, the app imports credentials from each enabled supported emulator, patches the emulator config, then starts the proxy service.

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

:::

## Automatic Reverting (Stop Proxy)

When you press **Stop proxy**, the app reverts any enabled emulator configs that were patched when the proxy started.

:::tabs key:android-emulator

== RetroArch

The app reverts `retroarch.cfg` to clear the custom server setting so RetroArch connects directly to RetroAchievements again.

== Dolphin

The app reverts `Config/RetroAchievements.ini` so Dolphin connects directly to RetroAchievements again.

:::

If hardcore mode was enabled before you started the proxy, it is automatically restored when you stop the proxy. The app records the original hardcore setting when patching and saves it so it survives process restarts.

## Checking Patch Status

The app checks the patch status on startup. The action bar proxy button reflects whether the proxy is running.

## Why Hardcore Mode is Disabled

The patcher disables hardcore mode because **hardcore mode is not supported** by RAOfflineProxy. Any hardcore award request is rejected by the proxy. Keeping hardcore enabled in a supported emulator while using the proxy would result in silent unlock failures.

When you stop the proxy, hardcore mode is restored to its original state: if you had it enabled before, it will be re-enabled automatically.
