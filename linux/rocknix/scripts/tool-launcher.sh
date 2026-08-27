#!/bin/bash
# RAOfflineProxy Tools launcher for ROCKNIX.
# Placed in /storage/.config/modules and launched by EmulationStation via foot.
# Runs the controller-driven SDL menu fullscreen under the sway compositor.
# The menu requests SDL fullscreen itself, so no sway_fullscreen call is needed.
#
# The SDL bundled inside the pygame-ce wheel is a generic manylinux build and
# is unreliable on these Rockchip/Mali devices, in two different ways:
#
#   1. On RK3326 with the libmali blob it segfaults creating a wayland window.
#   2. On the RG DS (RK3566, Mali-G52) it does not crash at all, it just renders
#      garbage: a correct 640x480 surface reaches the compositor as black with a
#      white top-right quadrant (issue #55, verified on-device with grim).
#
# ROCKNIX's own libSDL2, which every emulator on the device already uses, is
# built for this hardware and renders correctly in both cases, so it is
# preferred and the wheel's own SDL is only a fallback. Case 2 is why the
# preload cannot be chosen by exit code alone: nothing crashes, so a bad render
# still "succeeds".
#
# The video driver may also not be usable, so driver candidates are tried in
# separate attempts. Whichever combination survives is cached and tried first
# next time.

source /etc/profile

set_kill set "-9 raofflineproxy.main"

BASE_DIR="/storage/.local/share/raofflineproxy"

export HOME="/storage"
export XDG_CONFIG_HOME="/storage/.config"
export XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-/var/run/0-runtime-dir}"
export WAYLAND_DISPLAY="${WAYLAND_DISPLAY:-wayland-1}"
export LD_LIBRARY_PATH="${BASE_DIR}/lib${LD_LIBRARY_PATH:+:${LD_LIBRARY_PATH}}"
export PYTHONPATH="${BASE_DIR}/app:${BASE_DIR}"

# Without this the wayland app_id is not the binary name, so sway cannot match
# the window by app_id at all - give it a stable one to match on.
export SDL_VIDEO_WAYLAND_WMCLASS=raofflineproxy

CONFIG_DIR="/storage/.config/raofflineproxy"
# v2: the ordering below changed, so a cache written by an older build would
# pin the wheel's SDL again and reintroduce issue #55 on upgrade.
ATTEMPT_CACHE_FILE="${CONFIG_DIR}/rocknix_sdl_attempt2"
STDOUT_LOG="${CONFIG_DIR}/menu-stdout.log"
DRIVER_CANDIDATES=(wayland kmsdrm x11)
SYSTEM_SDL="$(ls /usr/lib/libSDL2-2.0.so.0* 2>/dev/null | head -n1)"

mkdir -p "${CONFIG_DIR}"

# Attempts are "<driver>|<preload>", an empty preload meaning the wheel's own SDL.
# The system library goes first: it is the one built for this hardware.
attempts=()
for d in "${DRIVER_CANDIDATES[@]}"; do
  [ -n "${SYSTEM_SDL}" ] && attempts+=("${d}|${SYSTEM_SDL}")
  attempts+=("${d}|")
done

ordered=()
if [ -r "${ATTEMPT_CACHE_FILE}" ]; then
  cached="$(cat "${ATTEMPT_CACHE_FILE}" 2>/dev/null)"
  for a in "${attempts[@]}"; do
    [ "${a}" = "${cached}" ] && ordered+=("${a}")
  done
fi
for a in "${attempts[@]}"; do
  already_queued=0
  for q in "${ordered[@]}"; do
    [ "${q}" = "${a}" ] && already_queued=1 && break
  done
  [ "${already_queued}" -eq 0 ] && ordered+=("${a}")
done

exit_code=0
for attempt in "${ordered[@]}"; do
  driver="${attempt%%|*}"
  preload="${attempt#*|}"

  echo "=== $(date) attempt driver=${driver} preload=${preload:-none} ===" >> "${STDOUT_LOG}"
  # A native SDL crash never reaches Python, so the only record of it is what
  # SDL and the loader wrote to stdout/stderr. Keep it instead of letting it
  # vanish with the foot terminal.
  SDL_VIDEODRIVER="${driver}" LD_PRELOAD="${preload}" \
    /usr/bin/python3 -m raofflineproxy.main menu 2>&1 | tee -a "${STDOUT_LOG}"
  exit_code="${PIPESTATUS[0]}"
  echo "=== exit_code=${exit_code} ===" >> "${STDOUT_LOG}"

  if [ "${exit_code}" -lt 128 ]; then
    echo "${attempt}" > "${ATTEMPT_CACHE_FILE}"
    break
  fi
  # exit_code >= 128 means the process died from a signal (e.g. 139 = SIGSEGV);
  # try the next combination instead of surfacing a crash for a fixable case.
done

exit "${exit_code}"
