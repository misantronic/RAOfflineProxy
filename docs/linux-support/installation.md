# Installation

Linux installation differs by target, but the goal is the same on both supported targets: start the proxy while online once, then cache each game you want to play offline.

## Prerequisites

Before using `RAOfflineProxy` on Linux:

:::tabs key:linux-target

== KNULLI

- Enter your RetroAchievements account details in RetroArch

== Onion

- Make sure the system clock is correct
- Enter your RetroAchievements account details in RetroArch

:::

::: warning RetroArch config is patched automatically
RAOfflineProxy patches the RetroArch config it needs in order to redirect RetroAchievements traffic through the local proxy. For target-specific details, see [Emulator Patching](/linux-support/cfg-patching).
:::

## Install Flow

:::tabs key:linux-target

== KNULLI

> You are installing the current alpha KNULLI build.

1. Download the KNULLI installer from [GitHub Releases](https://github.com/misantronic/RAOfflineProxy/releases)
2. Copy it into:

```text
/userdata/roms/tools
```

3. Refresh or update gamelists so **RAOfflineProxy Install** appears in the **Tools** menu
4. Launch **RAOfflineProxy Install** from **Tools**
5. Refresh or update gamelists again so the main **RAOfflineProxy** entry appears

== Onion

> Onion support is currently experimental but working.

1. Download the latest release for Onion from [GitHub Releases](https://github.com/misantronic/RAOfflineProxy/releases)
2. Copy the generated app folder to:

```text
/App/RAOfflineProxy/
```

3. Launch **RAOfflineProxy** from Onion's Apps section
4. Start the proxy from the Onion menu

:::

## First Online Run

:::tabs key:linux-target

== KNULLI

1. Launch **RAOfflineProxy** from the **Tools** menu
2. Start the proxy from the on-device menu
3. Stay online and cache games either from **Cached Games** then **Add ROM** or by launching the game once in RetroArch

== Onion

1. Start the proxy while online
2. Launch a game once so its data is cached

That prepares the game for offline use.

:::

See [Caching Games](/linux-support/caching-games) for more detail.

## Play Offline

:::tabs key:linux-target

== KNULLI

1. Start the proxy from the KNULLI menu
2. Launch RetroArch and load a cached game
3. Earn achievements while offline
4. Reconnect later and let queued awards flush automatically

== Onion

1. Launch **RAOfflineProxy** from Onion's Apps section
2. Start the proxy
3. Launch a game you already cached while online
4. Earn achievements while offline
5. Reconnect later and let queued awards flush automatically

:::
