#!/bin/bash
# RAOfflineProxy SDL diagnostic for ROCKNIX.
#
# The menu segfaults inside pygame.display.set_mode() on some devices with no
# Python traceback, and the launcher discards stdout/stderr, so the actual SDL
# error and the kernel's segfault record were never captured. This runs the
# window creation in isolation across a matrix of SDL configurations, keeping
# stdout, stderr, exit signal and dmesg for each attempt in one report.

source /etc/profile

BASE_DIR="/storage/.local/share/raofflineproxy"
REPORT="/storage/.config/raofflineproxy/sdl-doctor.log"

export HOME="/storage"
export XDG_CONFIG_HOME="/storage/.config"
export XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-/var/run/0-runtime-dir}"
export WAYLAND_DISPLAY="${WAYLAND_DISPLAY:-wayland-1}"
export LD_LIBRARY_PATH="${BASE_DIR}/lib${LD_LIBRARY_PATH:+:${LD_LIBRARY_PATH}}"
export PYTHONPATH="${BASE_DIR}/app:${BASE_DIR}"

PROBE="${BASE_DIR}/app/sdl_probe.py"
BUNDLED_SDL="$(ls "${BASE_DIR}"/app/pygame_ce.libs/libSDL2-2-*.so.* 2>/dev/null | head -n1)"
SYSTEM_SDL="$(ls /usr/lib/libSDL2-2.0.so.0* 2>/dev/null | head -n1)"

mkdir -p "$(dirname "${REPORT}")"
: > "${REPORT}"

log() {
  echo "$*" >> "${REPORT}"
}

log "=== RAOfflineProxy SDL doctor ==="
log "date: $(date)"
log "kernel: $(uname -a)"
log "os: $(cat /etc/os-release 2>/dev/null | tr '\n' ' ')"
log "python: $(/usr/bin/python3 -V 2>&1)"
log ""

log "=== environment ==="
log "WAYLAND_DISPLAY=${WAYLAND_DISPLAY}"
log "XDG_RUNTIME_DIR=${XDG_RUNTIME_DIR}"
log "wayland sockets: $(ls -1 "${XDG_RUNTIME_DIR}"/wayland-* 2>&1 | tr '\n' ' ')"
log "bundled SDL: ${BUNDLED_SDL:-<none>}"
log "system SDL:  ${SYSTEM_SDL:-<none>}"
log ""

log "=== system libraries SDL dlopens ==="
for lib in libEGL.so.1 libGLESv2.so.2 libwayland-client.so.0 libwayland-egl.so.1 libdecor-0.so.0 libgbm.so.1 libdrm.so.2; do
  found="$(ls /usr/lib/"${lib}"* 2>/dev/null | head -n1)"
  log "${lib}: ${found:-MISSING}"
done
log ""

log "=== GPU ==="
log "drm cards: $(ls -1 /dev/dri 2>&1 | tr '\n' ' ')"
log "mali module params: $(ls -1 /sys/module 2>/dev/null | grep -iE 'mali|bifrost|panfrost' | tr '\n' ' ')"
log ""

run_probe() {
  local name="$1"
  shift

  log "--- probe: ${name} ---"
  log "env: $*"

  dmesg -c >/dev/null 2>&1

  local out
  out="$(env "$@" /usr/bin/python3 "${PROBE}" 2>&1)"
  local code=$?

  log "${out}"
  log "exit_code=${code}"

  if [ "${code}" -ge 128 ]; then
    local signal=$((code - 128))
    case "${signal}" in
      11) log "died from SIGSEGV (segfault)" ;;
      6)  log "died from SIGABRT" ;;
      4)  log "died from SIGILL (illegal instruction)" ;;
      7)  log "died from SIGBUS" ;;
      *)  log "died from signal ${signal}" ;;
    esac
    log "dmesg:"
    dmesg 2>/dev/null | grep -iE "segfault|trap|Code:|python3|mali" | tail -n 15 >> "${REPORT}"
  fi

  log ""
}

# Controls: do NOT touch the GPU or compositor. If these crash, the pygame
# build itself is broken rather than the wayland/EGL path.
run_probe "dummy driver (control)" SDL_VIDEODRIVER=dummy
run_probe "offscreen driver (control)" SDL_VIDEODRIVER=offscreen

# Baseline: what the menu actually does today.
run_probe "wayland baseline" SDL_VIDEODRIVER=wayland SDL_LOGGING=video=verbose

# libdecor is dlopened from the system and is a known crash source when the
# wheel's SDL was built against a different version than the OS ships.
run_probe "wayland, libdecor off" \
  SDL_VIDEODRIVER=wayland SDL_VIDEO_WAYLAND_ALLOW_LIBDECOR=0 SDL_LOGGING=video=verbose

run_probe "wayland, no framebuffer accel" \
  SDL_VIDEODRIVER=wayland SDL_FRAMEBUFFER_ACCELERATION=0 SDL_LOGGING=video=verbose

run_probe "wayland, libdecor off + no accel" \
  SDL_VIDEODRIVER=wayland SDL_VIDEO_WAYLAND_ALLOW_LIBDECOR=0 \
  SDL_FRAMEBUFFER_ACCELERATION=0 SDL_LOGGING=video=verbose

run_probe "wayland, windowed" \
  SDL_VIDEODRIVER=wayland PROBE_MODE=windowed SDL_LOGGING=video=verbose

# The wheel bundles its own SDL 2.32.10 but borrows the system's wayland/EGL
# stack. Every emulator on this device drives the same screen through the
# system SDL, so try that combination instead.
if [ -n "${SYSTEM_SDL}" ]; then
  run_probe "wayland, system SDL2 preloaded" \
    SDL_VIDEODRIVER=wayland LD_PRELOAD="${SYSTEM_SDL}" SDL_LOGGING=video=verbose
else
  log "--- probe: system SDL2 preloaded --- SKIPPED (no system SDL2 found)"
  log ""
fi

run_probe "kmsdrm" SDL_VIDEODRIVER=kmsdrm SDL_LOGGING=video=verbose

log "=== done ==="
log "Report written to ${REPORT}"

echo "SDL doctor finished."
echo "Send this file to the developer:"
echo "  ${REPORT}"
echo ""
echo "Press any key to close."
read -r -n 1 -s
