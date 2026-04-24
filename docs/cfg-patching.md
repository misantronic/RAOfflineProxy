# RetroArch CFG Patching

## Overview

RetroArch stores its configuration in a file called `retroarch.cfg`. To redirect achievement traffic to the local proxy, RAOfflineProxy changes two settings in this file:

- The **custom achievement server** is pointed at the proxy on your device
- **Hardcore mode** is disabled (since it is not supported)

Patching and reverting happen **automatically** when you start and stop the proxy — there is no separate setup step.

## Automatic Patching (Start Proxy)

When you press **Start proxy** in the action bar, the app patches `retroarch.cfg` before starting the proxy service. If the app needs folder access to write the file, it shows a dialog prompting you to grant access.

Before patching, the app also checks for a one-time sibling backup named `retroarch.raofflineproxy.cfg`. If that file does not already exist, RAOfflineProxy creates it from the current `retroarch.cfg`.

- The backup is created only once
- The app does not overwrite it later
- The app does not restore from it automatically

The app tries four strategies in order:

### Strategy 1 — Folder Access (preferred)

If you have previously granted folder access, the app uses the saved permission to read and write the file directly. No extra prompts.

### Strategy 2 — Direct File Write

The app checks common RetroArch installation paths for a writable `retroarch.cfg`. If the file is found and writable, it is patched in place.

### Strategy 3 — Folder Access Prompt (Android 12 and below)

On Android 12 and below, if the file is found but not directly writable, the app shows a dialog asking you to grant folder access. Tapping **Grant** opens the system folder picker. Grant access to the folder containing `retroarch.cfg` (typically inside the RetroArch data folder). After granting, the app retries automatically.

### Strategy 4 — Staging Copy

If all else fails, the app copies `retroarch.cfg` to a temporary folder, patches it there, and attempts to copy it back. If the copy-back fails, you are shown the file path and must manually copy it.

## Automatic Reverting (Stop Proxy)

When you press **Stop proxy**, the app reverts `retroarch.cfg` to clear the custom server setting so RetroArch connects directly to RetroAchievements again.

If hardcore mode was enabled before you started the proxy, it is automatically restored when you stop the proxy. The app records the original hardcore setting when patching and saves it so it survives process restarts.

## Important Shutdown Caveat

- Always stop sync before killing the app.
- On some devices, swiping the app away or crashing while the proxy is active does not reliably revert `retroarch.cfg` immediately.
- If the app was killed or crashed during sync, reopen RAOfflineProxy once so it can restore the config on launch.

## Checking Patch Status

The app checks the patch status on startup. The action bar proxy button reflects whether the proxy is running.

## Manual Patching (adb fallback)

If the app cannot patch the file automatically, you can do it manually via adb. Open `retroarch.cfg` and set:

- The custom achievement server to `127.0.0.1:8080`
- Hardcore mode to disabled

To revert manually, clear the custom achievement server value (set it to empty).

## Why Hardcore Mode is Disabled

The patcher disables hardcore mode because **hardcore mode is not supported** by RAOfflineProxy. Any hardcore award request is rejected by the proxy. Keeping hardcore enabled in RetroArch while using the proxy would result in silent unlock failures.

When you stop the proxy, hardcore mode is restored to its original state — so if you had it enabled before, it will be re-enabled automatically.
