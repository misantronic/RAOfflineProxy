#!/bin/bash
set -u

DIR="$(cd "$(dirname "$0")" && pwd)"
BASE_DIR="$DIR"
APP_DIR="$BASE_DIR/app"
LIB_DIR="$BASE_DIR/../lib"
PYTHON="${RAOFFLINEPROXY_PYTHON:-/usr/bin/python3}"
CONFIG_DIR="${RAOFFLINEPROXY_CONFIG_DIR:-/data/banana/raofflineproxy}"
RETROARCH_CFG="${RAOFFLINEPROXY_RETROARCH_CFG:-/data/retroarch/retroarch.cfg}"
LOG_FILE="${CONFIG_DIR}/launcher.log"

if [ ! -x "$PYTHON" ]; then
  echo "RAOfflineProxy: missing $PYTHON" >&2
  exit 1
fi

export HOME="${HOME:-/data}"
export XDG_CONFIG_HOME="${XDG_CONFIG_HOME:-/data/banana}"
export RAOFFLINEPROXY_CONFIG_DIR="$CONFIG_DIR"
export RAOFFLINEPROXY_RETROARCH_CFG="$RETROARCH_CFG"
export PYTHONPATH="$APP_DIR:$BASE_DIR/..${PYTHONPATH:+:$PYTHONPATH}"
export LD_LIBRARY_PATH="$LIB_DIR${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"

mkdir -p "$CONFIG_DIR"
if [ ! -f "$CONFIG_DIR/config.json" ]; then
  printf '%s\n' '{"proxy_host":"127.0.0.1","proxy_port":18080,"retroarch_cfg":"/data/retroarch/retroarch.cfg"}' > "$CONFIG_DIR/config.json"
fi

enable_loopback() {
    # BananaOS 0.5.0 brings lo up during boot but does not assign 127.0.0.1/8.
    # Check every interface first: a future image or user configuration may
    # already assign the address somewhere other than lo.
    if command -v ip >/dev/null 2>&1; then
        ip -4 addr show 2>/dev/null | grep -q 'inet 127\.0\.0\.1/' && return 0
        ip link set lo up 2>/dev/null || true
        ip addr add 127.0.0.1/8 dev lo 2>/dev/null || true
    elif command -v ifconfig >/dev/null 2>&1; then
        ifconfig -a 2>/dev/null | grep -q '127\.0\.0\.1' && return 0
        ifconfig lo 127.0.0.1 netmask 255.0.0.0 up 2>/dev/null || true
    fi
}

run_core() {
  "$PYTHON" -m raofflineproxy.main "$1" --retroarch-cfg "$RETROARCH_CFG" 2>&1 | tee -a "$LOG_FILE"
  return "${PIPESTATUS[0]}"
}

case "${1:-start}" in
  start)
    enable_loopback
    run_core start-proxy
    ;;
  stop)
    run_core stop-proxy
    ;;
  restart)
    enable_loopback
    "$PYTHON" -m raofflineproxy.main stop-proxy --retroarch-cfg "$RETROARCH_CFG" || true
    run_core start-proxy
    ;;
  status)
    run_core service-status
    ;;
  menu)
    enable_loopback
    export SDL_VIDEODRIVER="${SDL_VIDEODRIVER:-kmsdrm}"
    export SDL_KMSDRM_FIRST="${BANANA_TOP:-${SDL_KMSDRM_FIRST:-DSI-2}}"
    export SDL_GAMECONTROLLERCONFIG_FILE="$BASE_DIR/gamecontrollerdb.txt"
    for system_sdl in /usr/lib/libSDL2-2.0.so.0* /lib/libSDL2-2.0.so.0*; do
      if [ -f "$system_sdl" ]; then
        export LD_PRELOAD="$system_sdl${LD_PRELOAD:+:$LD_PRELOAD}"
        break
      fi
    done
    run_core menu-sdl
    ;;
  *)
    echo "Usage: $0 {start|stop|restart|status|menu}" >&2
    exit 2
    ;;
esac
