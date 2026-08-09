#!/bin/bash
# RAOfflineProxy Tools launcher for ROCKNIX.
# Placed in /storage/.config/modules and launched by EmulationStation via foot.
# Runs the controller-driven SDL menu fullscreen under the sway compositor.
# The menu requests SDL fullscreen itself, so no sway_fullscreen call is needed.
#
# Some devices (e.g. RK3326 with the libmali blob) segfault inside SDL's
# wayland/EGL init before the app's own pygame.error fallback can run, since a
# native crash never reaches Python. Try candidate SDL video drivers in
# separate attempts and remember whichever one first works.

source /etc/profile

set_kill set "-9 raofflineproxy.main"

BASE_DIR="/storage/.local/share/raofflineproxy"

export HOME="/storage"
export XDG_CONFIG_HOME="/storage/.config"
export XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-/var/run/0-runtime-dir}"
export WAYLAND_DISPLAY="${WAYLAND_DISPLAY:-wayland-1}"
export LD_LIBRARY_PATH="${BASE_DIR}/lib${LD_LIBRARY_PATH:+:${LD_LIBRARY_PATH}}"
export PYTHONPATH="${BASE_DIR}/app:${BASE_DIR}"

DRIVER_CACHE_FILE="/storage/.config/raofflineproxy/rocknix_video_driver"
STDOUT_LOG="/storage/.config/raofflineproxy/menu-stdout.log"
DRIVER_CANDIDATES=(wayland kmsdrm x11)

drivers_to_try=()
if [ -r "${DRIVER_CACHE_FILE}" ]; then
  cached_driver="$(cat "${DRIVER_CACHE_FILE}" 2>/dev/null)"
  for d in "${DRIVER_CANDIDATES[@]}"; do
    [ "${d}" = "${cached_driver}" ] && drivers_to_try+=("${d}")
  done
fi
for d in "${DRIVER_CANDIDATES[@]}"; do
  already_queued=0
  for q in "${drivers_to_try[@]}"; do
    [ "${q}" = "${d}" ] && already_queued=1 && break
  done
  [ "${already_queued}" -eq 0 ] && drivers_to_try+=("${d}")
done

mkdir -p "$(dirname "${STDOUT_LOG}")"

exit_code=0
for driver in "${drivers_to_try[@]}"; do
  echo "=== $(date) attempt driver=${driver} ===" >> "${STDOUT_LOG}"
  # A native SDL crash never reaches Python, so the only record of it is what
  # SDL and the loader wrote to stdout/stderr. Keep it instead of letting it
  # vanish with the foot terminal.
  SDL_VIDEODRIVER="${driver}" /usr/bin/python3 -m raofflineproxy.main menu \
    2>&1 | tee -a "${STDOUT_LOG}"
  exit_code="${PIPESTATUS[0]}"
  echo "=== exit_code=${exit_code} ===" >> "${STDOUT_LOG}"

  if [ "${exit_code}" -lt 128 ]; then
    mkdir -p "$(dirname "${DRIVER_CACHE_FILE}")"
    echo "${driver}" > "${DRIVER_CACHE_FILE}"
    break
  fi
  # exit_code >= 128 means the process died from a signal (e.g. 139 = SIGSEGV);
  # try the next driver instead of surfacing a crash for a fixable case.
done

exit "${exit_code}"
