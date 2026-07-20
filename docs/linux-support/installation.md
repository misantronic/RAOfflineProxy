# Installation

Linux installation differs by target, but the goal is the same on both supported targets: start the proxy while online once, then cache each game you want to play offline.

## Prerequisites

Before using `RAOfflineProxy` on Linux:

:::tabs key:linux-target

== KNULLI

- Enter your RetroAchievements account details<br>
  > Game Settings -> Retroachievement settings

== Onion

- Make sure the system clock is correct<br>
  > Apps -> Tweaks -> System -> Date and time -> Set automatically via internet
- Enter your RetroAchievements account details in RetroArch
- Reinstall:
  - Stop proxy in the app
  - Delete the old `/App/RAOfflineProxy` folder before copying a new version

== muOS

- Enter your RetroAchievements account details in RetroArch<br>
  > muOS stores these credentials in the `retroarch.cheevos.cfg` appendconfig, which RAOfflineProxy reads automatically.
- Make sure the system clock is correct so award timestamps are accurate

== ROCKNIX

- Enter your RetroAchievements account details<br>
  > Game Settings -> Retroachievement settings

== dArkOS

- Enter your RetroAchievements account details in RetroArch
- The installer installs `python3-pygame` via `apt` automatically using passwordless `sudo`; if that's not available on your setup, install it manually: `sudo apt install -y python3-pygame`

:::

::: warning Emulator configs are patched automatically
RAOfflineProxy patches the emulator configs it needs in order to redirect RetroAchievements traffic through the local proxy (RetroArch everywhere, plus standalone PPSSPP on ROCKNIX). For target-specific details, see [Emulator Patching](/linux-support/cfg-patching).
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

3. Update gamelists so **RAOfflineProxy Install** appears in the **Tools** menu
4. Launch **RAOfflineProxy Install** from **Tools**
5. Launch **RAOfflineProxy** from the **Tools** menu
6. Start the proxy while online
7. Cache games either from **Cached Games** then **Add ROM** or by launching them once in RetroArch

== Onion

> Onion support is currently in alpha.
>
> Compatible with [OnionOS v4.3.1-1](https://github.com/OnionUI/Onion/releases/tag/v4.3.1-1) and [Onion V4.4.0-beta-20260120](https://github.com/OnionUI/Onion/releases/tag/latest).

1. Download the latest release for Onion from [GitHub Releases](https://github.com/misantronic/RAOfflineProxy/releases)
2. Copy the generated app folder to:

```text
/App/RAOfflineProxy/
```

3. Launch **RAOfflineProxy** from Onion's Apps section
4. Start the proxy while online
5. Launch a game once so its data is cached

== muOS

> muOS support is currently in alpha.
>
> Compatible with [MustardOS 2601.1 Funky Jacaranda](https://muos.dev/release/current/2601_1).

1. Download the latest `RAOfflineProxy-muOS-*.muxapp` from [GitHub Releases](https://github.com/misantronic/RAOfflineProxy/releases)
2. Copy the `.muxapp` into your muOS `ARCHIVE` folder on the SD card
3. Open **Archive Manager** (under **Applications**) and install the RAOfflineProxy archive
4. Launch **RAOfflineProxy** from the **Applications** menu

   The app payload is installed under:

```text
/run/muos/storage/application/RAOfflineProxy
```

5. Start the proxy while online
6. Launch a game once so its data is cached

== ROCKNIX

> ROCKNIX support is currently in alpha.

1. Download the latest `RAOfflineProxy-Rocknix-*-Install.sh` from [GitHub Releases](https://github.com/misantronic/RAOfflineProxy/releases)
2. Copy the installer into:

```text
/storage/.config/modules
```

3. Update gamelists so the installer appears in the **Tools** menu
4. Launch the installer from **Tools**

   The app payload is installed under:

```text
/storage/.local/share/raofflineproxy
```

5. Launch **RAOfflineProxy** from the **Tools** menu
6. Start the proxy while online
7. Cache games either from **Cached Games** then **Add ROM** or by launching them once in RetroArch

== dArkOS

> dArkOS support is community-contributed and not yet verified by the developer on real hardware.

1. Download the latest `RAOfflineProxy-DarkOS-*-Install.sh` from [GitHub Releases](https://github.com/misantronic/RAOfflineProxy/releases)
2. Copy it into:

```text
/roms/tools
```

3. Refresh or update gamelists so **RAOfflineProxy Install** appears in the **Tools** menu
4. Launch **RAOfflineProxy Install** from **Tools** — no SSH or root needed; it uses passwordless `sudo` for the few steps that need it (same as dArkOS's own Tools scripts)
5. Refresh or update gamelists again so the main **RAOfflineProxy** entry appears
6. Launch **RAOfflineProxy** from the **Tools** menu
7. Start the proxy while online
8. Cache games either from **Cached Games** then **Add ROM** or by launching them once in RetroArch

:::

See [Caching Games](/linux-support/caching-games) for more detail.

## Offline Use

1. Start the proxy.
2. Launch a game you already cached.
3. Earn achievements while offline.
4. Reconnect later and let queued awards flush automatically.
