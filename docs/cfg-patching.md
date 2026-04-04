# RetroArch CFG Patching

## Overview

RetroArch stores its configuration in a file called `retroarch.cfg`. To redirect achievement traffic to the local proxy, RAOfflineProxy writes two settings:

```ini
cheevos_custom_host = "127.0.0.1:8080"
cheevos_hardcore_mode_enable = "false"
```

The **RetroArch Setup** screen in the app manages patching and reverting this file.

## Patching

Tap **Patch retroarch.cfg** on the RetroArch Setup screen. The app tries four strategies in order:

### Strategy 1 — SAF Tree URI (preferred)

If you have previously granted folder access via **Grant Folder Access**, the app uses the saved Storage Access Framework (SAF) URI to read and write the file directly. No extra prompts.

### Strategy 2 — Direct File Write

The app checks these paths in order for a writable `retroarch.cfg`:

```
/sdcard/Android/data/com.retroarch.aarch64/files/retroarch.cfg
/storage/emulated/0/Android/data/com.retroarch.aarch64/files/retroarch.cfg
/sdcard/Android/data/com.retroarch/files/retroarch.cfg
/storage/emulated/0/Android/data/com.retroarch/files/retroarch.cfg
/sdcard/RetroArch/retroarch.cfg
/storage/emulated/0/RetroArch/retroarch.cfg
```

If the file is found and writable, it is patched in place.

### Strategy 3 — SAF Grant Prompt (Android ≤ 12)

On Android 12 and below, if the file is found but not directly writable, the app shows a **Grant Folder Access** button. Tapping it opens the system folder picker. Grant access to the folder containing `retroarch.cfg` (typically `Android/data/com.retroarch.aarch64/files`). After granting, the app retries automatically.

### Strategy 4 — Staging Copy

If all else fails, the app copies `retroarch.cfg` to `/sdcard/RAOfflineProxy/retroarch.cfg`, patches it there, and attempts to copy it back. If the copy-back fails, you are shown a path and must manually copy the file.

## Reverting

Tap **Revert retroarch.cfg** on the RetroArch Setup screen. This sets:

```ini
cheevos_custom_host = ""
```

::: warning
The **Revert** button is disabled while the proxy service is running. Stop the proxy first.
:::

## Checking Patch Status

The Home screen shows whether `retroarch.cfg` is currently patched. A "Patch not applied" warning is shown if the cfg is not patched while the proxy is running.

## Manual Patching (adb fallback)

If the app cannot patch the file automatically, you can do it manually via adb:

```bash
adb shell "sed -i \
  's/cheevos_custom_host = .*/cheevos_custom_host = \"127.0.0.1:8080\"/' \
  /data/user/0/com.retroarch.aarch64/files/retroarch.cfg"

adb shell "sed -i \
  's/cheevos_hardcore_mode_enable = .*/cheevos_hardcore_mode_enable = \"false\"/' \
  /data/user/0/com.retroarch.aarch64/files/retroarch.cfg"
```

To revert manually:

```bash
adb shell "sed -i \
  's/cheevos_custom_host = .*/cheevos_custom_host = \"\"/' \
  /data/user/0/com.retroarch.aarch64/files/retroarch.cfg"
```

## Why Hardcore Mode is Disabled

The patcher forces `cheevos_hardcore_mode_enable = "false"` because **hardcore mode is not supported** by RAOfflineProxy. Any award request with `h=1` is rejected by the proxy with HTTP 403. Keeping hardcore enabled in RetroArch while using the proxy would result in silent unlock failures.
