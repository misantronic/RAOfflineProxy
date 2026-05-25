# Installation

Linux installation differs by target, but the goal is the same on both supported targets: start the proxy while online once, then cache each game you want to play offline.

## Prerequisites

Before using `RAOfflineProxy` on Linux:

:::tabs key:linux-target

== KNULLI

- Enter your RetroAchievements account details in RetroArch

== Onion

- Make sure the system clock is correct<br>
  > Apps -> Tweaks -> System -> Date and time -> Set automatically via internet
- Enter your RetroAchievements account details in RetroArch
- Delete the old `/App/RAOfflineProxy` folder before copying a new version

:::

::: warning RetroArch config is patched automatically
RAOfflineProxy patches the RetroArch config it needs in order to redirect RetroAchievements traffic through the local proxy. For target-specific details, see [Emulator Patching](/linux-support/cfg-patching).
:::

## Setup

:::tabs key:linux-target

== KNULLI

> Intended for [KNULLI Scarab](https://github.com/knulli-cfw/knulli-linux/releases/tag/20260511) and [KNULLI Gladiator II](https://github.com/knulli-cfw/distribution/releases/tag/20250813).

1. Download the KNULLI installer from [GitHub Releases](https://github.com/misantronic/RAOfflineProxy/releases)
2. Copy it into:

```text
/userdata/roms/tools
```

3. Refresh or update gamelists so **RAOfflineProxy Install** appears in the **Tools** menu
4. Launch **RAOfflineProxy Install** from **Tools**
5. Refresh or update gamelists again so the main **RAOfflineProxy** entry appears
6. Launch **RAOfflineProxy** from the **Tools** menu
7. Start the proxy while online
8. Cache games either from **Cached Games** then **Add ROM** or by launching them once in RetroArch

== Onion

> Onion support is currently in alpha.
>
> Compatible with [OnionOS v4.3.1-1](https://github.com/OnionUI/Onion/releases/tag/latest) and `Onion V4.4.0-beta-20260120`.

1. Download the latest release for Onion from [GitHub Releases](https://github.com/misantronic/RAOfflineProxy/releases)
2. Copy the generated app folder to:

```text
/App/RAOfflineProxy/
```

3. Launch **RAOfflineProxy** from Onion's Apps section
4. Start the proxy while online
5. Launch a game once so its data is cached

:::

See [Caching Games](/linux-support/caching-games) for more detail.

## Offline Use

1. Start the proxy.
2. Launch a game you already cached.
3. Earn achievements while offline.
4. Reconnect later and let queued awards flush automatically.
