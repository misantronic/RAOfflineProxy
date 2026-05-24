#!/bin/sh
set -eu

if [ "$#" -gt 0 ]; then
  ACTION="$1"
  shift
else
  ACTION="patch"
fi

case "$ACTION" in
  patch|revert)
    ;;
  *)
    echo "Usage: ./RAOfflineProxy-setup.sh {patch|revert}"
    exit 1
    ;;
esac

CONFIG_PATH="/storage/emulated/0/Android/data/com.raofflineproxy/files/manual-emulator-setup/adb-config.json"

if ! command -v adb >/dev/null 2>&1; then
  echo "adb was not found in PATH."
  echo "Install Android platform-tools and try again."
  exit 1
fi

if ! adb shell "test -f '$CONFIG_PATH'" >/dev/null 2>&1; then
  echo "Could not find RAOfflineProxy manual setup config at $CONFIG_PATH"
  echo "Open RAOfflineProxy once so it can export the ADB setup config file."
  exit 1
fi

config_json="$(adb shell cat "$CONFIG_PATH" | tr -d '\r')"
PORT="$(printf '%s' "$config_json" | sed -n 's/.*"proxyPort"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1/p')"
config_flat="$(printf '%s' "$config_json" | tr -d '\n')"
EMULATORS="$(printf '%s' "$config_flat" | sed -n 's/.*"enabledEmulators"[[:space:]]*:[[:space:]]*\[\([^]]*\)\].*/\1/p' | tr -d '" ' | tr ',' ',')"
if [ -z "$EMULATORS" ]; then
  EMULATORS="$(printf '%s' "$config_flat" | sed -n 's/.*"enabledEmulators"[[:space:]]*:[[:space:]]*"\[\([^]]*\)\]".*/\1/p' | tr -d '" ' | tr ',' ',')"
fi

if [ -z "$PORT" ] || [ -z "$EMULATORS" ]; then
  echo "RAOfflineProxy exported config is missing the proxy port or enabled emulators."
  exit 1
fi

if [ "$ACTION" = "revert" ]; then
  EMULATORS="retroarch,dolphin"
fi

RETROARCH_TARGETS="
/storage/emulated/0/Android/data/com.retroarch.aarch64/files/retroarch.cfg
/storage/emulated/0/Android/data/com.retroarch/files/retroarch.cfg
/storage/emulated/0/RetroArch/retroarch.cfg
"

RETROARCH_PACKAGES="
com.retroarch.aarch64
com.retroarch
"

DOLPHIN_TARGETS="
/storage/emulated/0/Android/data/org.dolphinemu.dolphinemu/files/Config/RetroAchievements.ini
/storage/emulated/0/Android/data/org.dolphinemu.dolphinemu.beta/files/Config/RetroAchievements.ini
/storage/emulated/0/Android/data/org.dolphinemu.dolphinemu.debug/files/Config/RetroAchievements.ini
/storage/emulated/0/dolphin-emu/Config/RetroAchievements.ini
"

DOLPHIN_PACKAGES="
org.dolphinemu.dolphinemu
org.dolphinemu.dolphinemu.beta
org.dolphinemu.dolphinemu.debug
"

TMPDIR="$(mktemp -d /tmp/raofflineproxy-setup.XXXXXX)"
cleanup() {
  rm -rf "$TMPDIR"
}
trap cleanup EXIT

has_emulator() {
  case ",$EMULATORS," in
    *",$1,"*) return 0 ;;
    *) return 1 ;;
  esac
}

ensure_line() {
  file="$1"
  key="$2"
  value="$3"

  perl -0pi -e "s/^\\s*${key}\\s*=.*$/${key} = \"${value}\"/mg; END { if (\$_ !~ /^\\s*${key}\\s*=/m) { s/\\s*\\z/\\n${key} = \"${value}\"\\n/ } }" "$file"
}

replace_line() {
  file="$1"
  key="$2"
  value="$3"

  perl -0pi -e "s/^\\s*${key}\\s*=.*$/${key} = \"${value}\"/mg" "$file"
}

stop_packages_if_running() {
  emulator_name="$1"
  packages="$2"
  stopped_any=0

  for package in $packages; do
    pid="$(adb shell pidof "$package" 2>/dev/null | tr -d '\r')"
    if [ -n "$pid" ]; then
      echo "$emulator_name is running. Closing $package before $ACTION."
      adb shell am force-stop "$package" >/dev/null
      stopped_any=1
    fi
  done

  if [ "$stopped_any" -eq 1 ]; then
    sleep 1
  fi
}

patch_retroarch() {
  patched=0

  for target in $RETROARCH_TARGETS; do
    if adb shell "test -f '$target'"; then
      tmpfile="$TMPDIR/retroarch.cfg"
      echo "Found RetroArch config at $target"
      adb pull "$target" "$tmpfile" >/dev/null
      ensure_line "$tmpfile" "cheevos_custom_host" "127.0.0.1:$PORT"
      ensure_line "$tmpfile" "cheevos_hardcore_mode_enable" "false"
      adb push "$tmpfile" "$target" >/dev/null
      echo "Patched RetroArch for port $PORT."
      patched=1
      break
    fi
  done

  if [ "$patched" -eq 0 ]; then
    echo "Could not find RetroArch config in any supported location."
    return 1
  fi
}

revert_retroarch() {
  reverted=0

  for target in $RETROARCH_TARGETS; do
    if adb shell "test -f '$target'"; then
      tmpfile="$TMPDIR/retroarch.cfg"
      echo "Found RetroArch config at $target"
      adb pull "$target" "$tmpfile" >/dev/null
      replace_line "$tmpfile" "cheevos_custom_host" ""
      adb push "$tmpfile" "$target" >/dev/null
      echo "Reverted RetroArch proxy host."
      reverted=1
      break
    fi
  done

  if [ "$reverted" -eq 0 ]; then
    echo "Could not find RetroArch config in any supported location."
    return 1
  fi
}

patch_dolphin() {
  patched=0

  for target in $DOLPHIN_TARGETS; do
    if adb shell "test -f '$target'"; then
      tmpfile="$TMPDIR/RetroAchievements.ini"
      echo "Found Dolphin config at $target"
      adb pull "$target" "$tmpfile" >/dev/null
      ensure_line "$tmpfile" "HostUrl" "127.0.0.1:$PORT"
      ensure_line "$tmpfile" "HardcoreEnabled" "False"
      adb push "$tmpfile" "$target" >/dev/null
      echo "Patched Dolphin for port $PORT."
      patched=1
      break
    fi
  done

  if [ "$patched" -eq 0 ]; then
    echo "Could not find Dolphin config in any supported location."
    return 1
  fi
}

revert_dolphin() {
  reverted=0

  for target in $DOLPHIN_TARGETS; do
    if adb shell "test -f '$target'"; then
      tmpfile="$TMPDIR/RetroAchievements.ini"
      echo "Found Dolphin config at $target"
      adb pull "$target" "$tmpfile" >/dev/null
      replace_line "$tmpfile" "HostUrl" ""
      adb push "$tmpfile" "$target" >/dev/null
      echo "Reverted Dolphin proxy host."
      reverted=1
      break
    fi
  done

  if [ "$reverted" -eq 0 ]; then
    echo "Could not find Dolphin config in any supported location."
    return 1
  fi
}

status=0

if has_emulator retroarch; then
  stop_packages_if_running "RetroArch" "$RETROARCH_PACKAGES"
  if [ "$ACTION" = "patch" ]; then
    patch_retroarch || status=1
  else
    revert_retroarch || status=1
  fi
fi

if has_emulator dolphin; then
  stop_packages_if_running "Dolphin" "$DOLPHIN_PACKAGES"
  if [ "$ACTION" = "patch" ]; then
    patch_dolphin || status=1
  else
    revert_dolphin || status=1
  fi
fi

exit "$status"
