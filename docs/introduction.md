# Introduction

> Current release stage: alpha (`v1.0.0-alpha1`). This is the first public prerelease and has not gone through formal QA.

## What is RAOfflineProxy?

RAOfflineProxy is a local proxy that acts between [RetroArch](https://www.retroarch.com/) and the [RetroAchievements](https://retroachievements.org/) (RA) website.

RetroArch's achievement system talks directly to RetroAchievements over the internet. This works great online, but the moment your connection drops, achievements stop unlocking and games may fail to load their achievement lists at all.

RAOfflineProxy sits transparently in the middle:

```
RetroArch
    │  connects to the proxy on your device
    ▼
RAOfflineProxy (local proxy)
    │
    ├─ online  ──► retroachievements.org  (saves response locally)
    └─ offline ──► local database         (serves saved response)
```

## What it does

| Situation | Behaviour |
|---|---|
| **Online — normal request** (game data, unlocks, etc.) | Forwards to RA, saves the response locally |
| **Offline — saved data available** | Serves the saved response as if you were online |
| **Offline — achievement earned (softcore)** | Queues the award locally, tells RetroArch it succeeded |
| **Back online** | Automatically sends all queued awards to RA |
| **Hardcore mode** | Always rejected — hardcore is not supported |

## What it does NOT do

- **Hardcore mode is not supported.** Any hardcore achievement unlock is immediately rejected. Starting the proxy also disables hardcore mode in RetroArch's config (and restores it when you stop the proxy).
- It does not modify RetroArch or any emulator core.
- It does not store ROM files or any game content.
- Approved by RetroAchievements.org.

## Important Shutdown Behavior

- Always stop sync before killing the app.
- On some devices, swiping the app away or crashing while the proxy is active does not immediately revert `retroarch.cfg`.
- If that happens, reopen RAOfflineProxy once so it can clean up the config on launch.

## Requirements

These requirements are for the Android app:

- Android **8.0** or newer
- RetroArch installed (any variant)
- A valid RetroAchievements account configured in RetroArch

## How the proxy is transparent to RetroArch

RetroArch has a built-in setting for a custom achievement server. RAOfflineProxy points that setting at itself on your device when you start the proxy. RetroArch then sends all achievement traffic to the local proxy instead of directly to RetroAchievements. No modification to RetroArch is needed — it is a standard setting.
