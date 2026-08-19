# Emulator Patching

You normally do not need to edit RetroArch config yourself. Starting the proxy patches the RetroAchievements settings that `RAOfflineProxy` needs. Stopping the proxy removes that patch again.

## RetroArch Config Location

:::tabs key:linux-target

== KNULLI

```text
/userdata/system/configs/retroarch/retroarch.cfg
```

== Onion

```text
/mnt/SDCARD/RetroArch/.retroarch/retroarch.cfg
```

== muOS

```text
/opt/muos/share/info/config/retroarch.cfg
```

muOS RetroArch also loads a separate cheevos appendconfig, which stores your RetroAchievements credentials:

```text
/opt/muos/share/info/config/retroarch.cheevos.cfg
```

RAOfflineProxy keeps both files in sync when patching and reverting.

== ROCKNIX

```text
/storage/.config/retroarch/retroarch.cfg
```

ROCKNIX also ships standalone PPSSPP with its own RetroAchievements support. When `ppsspp.ini` exists, RAOfflineProxy patches it too:

```text
/storage/.config/ppsspp/PSP/SYSTEM/ppsspp.ini
```

== spruce

spruce launches RetroArch with its own per-device config rather than the usual one, so that is the file RAOfflineProxy patches:

```text
/mnt/SDCARD/RetroArch/platform/retroarch-<device>.cfg
```

spruce rewrites the account and achievement keys in this file on every game launch from its own RetroAchievements settings, so starting the proxy also switches spruce's achievements mode to `Softcore`. Stopping the proxy restores whatever it was before.

:::

## What Gets Patched

While the proxy is active, `RAOfflineProxy` patches RetroArch:

- `cheevos_enable = "true"`
- `cheevos_custom_host = "<proxy_host>:<proxy_port>"`
- `cheevos_hardcore_mode_enable = "false"`

On ROCKNIX, standalone PPSSPP is patched the same way:

- `AchievementsHost = <proxy_host>:<proxy_port>`
- `AchievementsChallengeMode = False`

Hardcore mode is not supported.

## Reverting

When you stop the proxy, RAOfflineProxy removes the custom host again.

When exact saved patch state is available, previous values are restored. When saved state is missing, the fallback revert path still removes the proxy host safely.
