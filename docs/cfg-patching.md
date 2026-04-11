# RetroArch CFG Patching

## Overview

RetroArch stores its configuration in a file called `retroarch.cfg`. To redirect achievement traffic to the local proxy, RAOfflineProxy changes two settings in this file:

- The **custom achievement server** is pointed at the proxy on your device
- **Hardcore mode** is disabled (since it is not supported)

The **RetroArch Setup** screen in the app manages patching and reverting this file.

## Patching

Tap **Patch retroarch.cfg** on the RetroArch Setup screen. The app tries four strategies in order:

### Strategy 1 — Folder Access (preferred)

If you have previously granted folder access via **Grant Folder Access**, the app uses the saved permission to read and write the file directly. No extra prompts.

### Strategy 2 — Direct File Write

The app checks common RetroArch installation paths for a writable `retroarch.cfg`. If the file is found and writable, it is patched in place.

### Strategy 3 — Folder Access Prompt (Android 12 and below)

On Android 12 and below, if the file is found but not directly writable, the app shows a **Grant Folder Access** button. Tapping it opens the system folder picker. Grant access to the folder containing `retroarch.cfg` (typically inside the RetroArch data folder). After granting, the app retries automatically.

### Strategy 4 — Staging Copy

If all else fails, the app copies `retroarch.cfg` to a temporary folder, patches it there, and attempts to copy it back. If the copy-back fails, you are shown the file path and must manually copy it.

## Reverting

Tap **Revert retroarch.cfg** on the RetroArch Setup screen. This clears the custom server setting so RetroArch connects directly to RetroAchievements again.

::: warning
The **Revert** button is disabled while the proxy service is running. Stop the proxy first.
:::

## Checking Patch Status

The Home screen shows whether `retroarch.cfg` is currently patched. A warning is shown if the config is not patched while the proxy is running.

## Manual Patching (adb fallback)

If the app cannot patch the file automatically, you can do it manually via adb. Open `retroarch.cfg` and set:

- The custom achievement server to `127.0.0.1:8080`
- Hardcore mode to disabled

To revert manually, clear the custom achievement server value (set it to empty).

## Why Hardcore Mode is Disabled

The patcher disables hardcore mode because **hardcore mode is not supported** by RAOfflineProxy. Any hardcore award request is rejected by the proxy. Keeping hardcore enabled in RetroArch while using the proxy would result in silent unlock failures.
