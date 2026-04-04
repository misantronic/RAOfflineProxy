# Introduction

## What is RAOfflineProxy?

RAOfflineProxy is an Android app that acts as a **local HTTP proxy** between [RetroArch](https://www.retroarch.com/) and the [RetroAchievements](https://retroachievements.org/) (RA) API.

RetroArch's achievement system (powered by rcheevos) communicates directly with `retroachievements.org`. This works great online, but the moment your connection drops, achievements stop unlocking and games may fail to load their achievement lists at all.

RAOfflineProxy sits transparently in the middle:

```
RetroArch (rcheevos)
    │  HTTP → 127.0.0.1:8080
    ▼
RAOfflineProxy (local proxy)
    │
    ├─ online  ──► retroachievements.org  (cache response)
    └─ offline ──► local Room database   (serve cached response)
```

## What it does

| Situation | Behaviour |
|---|---|
| **Online — cacheable request** (patch, unlocks, gameid, etc.) | Forwards to RA, caches the response locally |
| **Offline — cached data available** | Serves the cached response as if online |
| **Offline — award earned (softcore)** | Queues the award locally, returns a synthetic success to RetroArch |
| **Back online** | Automatically flushes all queued awards to RA |
| **Hardcore mode (`h=1`)** | Always rejected with HTTP 403 — hardcore is not supported |

## What it does NOT do

- **Hardcore mode is not supported.** Any hardcoe achievement unlock (`h=1`) is immediately rejected. The patcher also forces `cheevos_hardcore_mode_enable = "false"` in `retroarch.cfg`.
- It does not modify RetroArch or any emulator core.
- It does not store ROM files or any game content.
- It is not affiliated with or endorsed by RetroAchievements.org.

## Requirements

- Android **8.0 (API 26)** or newer
- RetroArch installed (any variant — `com.retroarch.aarch64` or `com.retroarch`)
- A valid RetroAchievements account with your username and API token stored in RetroArch

## How the proxy is transparent to RetroArch

RetroArch supports a `cheevos_custom_host` setting in `retroarch.cfg`. RAOfflineProxy writes `127.0.0.1:8080` into that field. RetroArch then sends all achievement API traffic to the local proxy instead of directly to `retroachievements.org`. No RetroArch modification is needed — it is a built-in setting.

::: tip
The proxy forwards your RetroArch `User-Agent` header verbatim to RA. This is required because RA's server rejects requests that do not carry the `rcheevos` user-agent.
:::
