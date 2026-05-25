# Manual Emulator Setup

## Overview

::: tip Version info
Will be supported from **v1.2.0-alpha1**
:::

**Manual Emulator Setup** is a fallback for devices where RAOfflineProxy can read emulator config files but cannot reliably overwrite them through Android storage APIs.

If automatic patching works on your device, use the normal flow instead. Manual setup is only for cases where **Start proxy** cannot patch the emulator config for you.

This setup requires some technical experience because it uses `adb`, USB debugging, and terminal commands on a computer.

## When to use it

Use manual setup if:

- RAOfflineProxy can start, but emulator patching [fails repeatedly](/compatibility)
- Your device has [stricter scoped-storage](/caveats) behavior

**If automatic patching works, you do not need this page.**

## What changes in manual mode

When you enable **Manual Emulator Setup** in the app:

- RAOfflineProxy stops patching or reverting emulator configs automatically when you press **Start proxy** or **Stop proxy**
- The app asks for your RetroAchievements username and password instead of importing credentials from emulator config files

`Start proxy` is still allowed in manual mode. The app does not block proxy startup just because it cannot verify emulator patch status.

## Prerequisites

Before using the helper scripts:

1. Install `adb` on your computer. The easiest way is to install [Android platform-tools](https://developer.android.com/tools/releases/platform-tools) from Google.
2. On your device, open `Settings > About`.
3. Tap `Build number` 7 times to unlock Developer options.
4. Go back to `Settings > System > Developer options`.
5. Turn on `USB debugging`.
6. Connect the device to your computer and accept the USB debugging prompt on the device.
7. Download the helper scripts from [manual-emulator-setup/adb](https://github.com/misantronic/RAOfflineProxy/tree/main/manual-emulator-setup/adb).

## Basic workflow

1. Enable **Manual Emulator Setup** in RAOfflineProxy.
2. Enter your RetroAchievements username and password in the app when prompted.
3. Connect the device to a computer with `adb` working.
4. Run the setup script for your computer in a terminal:

   On macOS or Linux:

   ```bash
   ./RAOfflineProxy-setup.sh patch
   ```

   On Windows:

   ```bash
   RAOfflineProxy-setup.bat patch
   ```

5. Start the proxy in RAOfflineProxy.
6. Launch the emulator and play as normal.
7. When you want the emulator to connect directly to RetroAchievements again, stop the proxy and run the setup script in a terminal with `revert`:

   On macOS or Linux:

   ```bash
   ./RAOfflineProxy-setup.sh revert
   ```

   On Windows:

   ```bat
   RAOfflineProxy-setup.bat revert
   ```

## Important behavior difference

In the normal automatic flow, starting and stopping the proxy also patches and reverts emulator config for you.

In manual mode, that part is your responsibility:

- If you run `patch`, the emulator expects RAOfflineProxy to be running
- If the proxy is not running, RetroAchievements requests will fail
- If you want to go back to direct RetroAchievements access, you must run `revert`

## Troubleshooting

If the scripts cannot find the app's setup config:

- Open RAOfflineProxy once on the device
- Make sure manual mode has been opened at least once
- Try the script again after reopening the app

If the emulator still uses old settings after patching or reverting:

- Fully close the emulator first
- Run the script again
- Relaunch the emulator after the script finishes

## Related pages

- [Android Installation](./installation)
- [Emulator Config Patching](./cfg-patching)
- [Troubleshooting / FAQ](./troubleshooting)
