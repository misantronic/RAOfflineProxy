# Introduction

> Current release stage: `v1.2.0-alpha1`. This is a public prerelease and has not gone through formal QA.

## What is RAOfflineProxy?

RAOfflineProxy is a local proxy that acts between supported emulators and [RetroAchievements](https://retroachievements.org/) (RA). It currently supports RetroArch and Dolphin on **Android**, and **KNULLI** and **Onion (alpha)** for Linux.

Their achievement systems talk directly to RetroAchievements over the internet. This works great online, but the moment your connection drops, achievements stop unlocking and games may fail to load their achievement lists at all.

RAOfflineProxy sits transparently in the middle:

```
Emulator
    │  connects to the proxy on your device
    ▼
RAOfflineProxy (local proxy)
    │
    ├─ online  ──► retroachievements.org  (saves response locally)
    └─ offline ──► local database         (serves saved response)
```

**This technique has been approved by RetroAchievements.org staff.**

## What it does

| Situation                                             | Behaviour                                                                      |
| ----------------------------------------------------- | ------------------------------------------------------------------------------ |
| **Online: normal request** (game data, unlocks, etc.) | Forwards to RA, saves the response locally                                     |
| **Offline: saved data available**                     | Serves the saved response as if you were online                                |
| **Offline: achievement earned (softcore)**            | Queues the award locally, tells the emulator it succeeded                      |
| **Back online**                                       | Automatically sends all queued awards to RA                                    |
| **Start proxy**                                       | Updates the supported emulator config automatically so it uses the local proxy |
| **Stop proxy**                                        | Reverts the config change so the emulator connects normally again              |

## What it does NOT do

- **Hardcore mode is not supported.** Any hardcore achievement unlock is immediately rejected. Starting the proxy also disables hardcore mode in supported emulator configs where applicable and restores the previous setting when you stop the proxy.
- It does not modify emulator binaries or emulator cores.
- It does not store ROM files or any game content.

## How the proxy is transparent to supported emulators

Supported emulators already know how to talk to RetroAchievements. RAOfflineProxy just redirects that traffic to the local proxy by patching the emulator's existing config. The emulator then sends achievement traffic to RAOfflineProxy instead of directly to RetroAchievements. No emulator binary modification is needed.
