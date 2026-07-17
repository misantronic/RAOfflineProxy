#!/bin/bash
# RAOfflineProxy Tools launcher for ROCKNIX.
# Placed in /storage/.config/modules and launched by EmulationStation via foot.
# Runs the controller-driven SDL menu fullscreen under the sway compositor.
# The menu requests SDL fullscreen itself, so no sway_fullscreen call is needed.

source /etc/profile

set_kill set "-9 raofflineproxy.main"

BASE_DIR="/storage/.local/share/raofflineproxy"

export HOME="/storage"
export XDG_CONFIG_HOME="/storage/.config"
export XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-/var/run/0-runtime-dir}"
export WAYLAND_DISPLAY="${WAYLAND_DISPLAY:-wayland-1}"
export SDL_VIDEODRIVER="wayland"
export LD_LIBRARY_PATH="${BASE_DIR}/lib${LD_LIBRARY_PATH:+:${LD_LIBRARY_PATH}}"
export PYTHONPATH="${BASE_DIR}/app:${BASE_DIR}"

exec /usr/bin/python3 -m raofflineproxy.main menu
