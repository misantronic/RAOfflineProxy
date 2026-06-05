# Manual Emulator Setup

## Overview

::: tip Version info
Supported from **v1.3.1-alpha1**
:::

**Manual Emulator Setup** is the fallback for Android 14+ devices where RAOfflineProxy cannot reliably patch emulator config files through normal Android storage APIs.

It uses **Shizuku** so RAOfflineProxy can patch and revert emulator configs directly on the device without a computer.

If automatic patching works on your device, use the normal flow instead.

## When to use it

Use manual setup if:

- RAOfflineProxy can start, but emulator patching [fails repeatedly](/compatibility)
- Your device has [stricter scoped-storage](/caveats) behavior
- You are on Android 14 or newer and want the safer fallback path for patching and reverting

**If automatic patching works, you do not need this page.**

## What changes in manual mode

When you enable **Manual Emulator Setup** in the app:

- The app asks for your RetroAchievements username and password instead of importing credentials from emulator config files
- You can enable **Shizuku** as the patching backend for manual mode
- Once Shizuku is enabled, pressing **Start proxy** patches the enabled emulators through Shizuku before starting the proxy
- Pressing **Stop proxy** reverts the enabled emulators through Shizuku after stopping the proxy

## Prerequisites

Before enabling Shizuku in RAOfflineProxy:

1. Install the [Shizuku app](https://github.com/RikkaApps/Shizuku/releases).
2. Start Shizuku on your device.
3. Open RAOfflineProxy and enable **Manual Emulator Setup**.
4. Enter your RetroAchievements username and password in the app when prompted.
5. Grant Shizuku access when RAOfflineProxy requests it.

## Basic workflow

1. Enable **Manual Emulator Setup** in RAOfflineProxy.
2. Enter your RetroAchievements username and password in the app when prompted.
3. Open the Shizuku section and grant access.
4. Enable Shizuku.
5. Fully quit RetroArch, Dolphin, PPSSPP, or any other supported emulator you were using.
6. Press **Start proxy** in RAOfflineProxy.
7. RAOfflineProxy patches the enabled emulators through Shizuku and starts the proxy.
8. Wait until RAOfflineProxy finishes, then relaunch the emulator and play as normal.
9. When you are done, press **Stop proxy**.
10. RAOfflineProxy stops the proxy and reverts the enabled emulators through Shizuku.

## Important behavior difference

In the normal automatic flow, RAOfflineProxy patches emulator configs through the app's standard storage access path.

In manual mode on Android 14+, RAOfflineProxy uses Shizuku instead.

- When Shizuku is enabled, the emulator expects RAOfflineProxy to be running after **Start proxy**
- Fully closing the emulator before **Start proxy** and relaunching it afterward helps ensure the patched settings are picked up
- If the proxy is not running, RetroAchievements requests will fail while the emulator is patched
- **Stop proxy** reverts the emulator config through Shizuku so the emulator can connect directly to RetroAchievements again

## Troubleshooting

If Shizuku is not ready in RAOfflineProxy:

- Make sure the Shizuku app is installed
- Start Shizuku again on the device
- Reopen RAOfflineProxy and grant permission again if needed

If the emulator still uses old settings after starting or stopping the proxy:

- Fully close the emulator first
- Start or stop the proxy again
- Relaunch the emulator after RAOfflineProxy finishes

## Related pages

- [Android Installation](./installation)
- [Emulator Config Patching](./cfg-patching)
- [Troubleshooting / FAQ](./troubleshooting)
