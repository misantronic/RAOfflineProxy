#!/bin/sh
set -eu

BASE_DIR="/run/muos/storage/application/RAOfflineProxy"
APP_DIR="${BASE_DIR}/app"
PYGAME_DIR="${BASE_DIR}/pygame"
PYGAME_LIBS_DIR="${BASE_DIR}/pygame.libs"
DATA_DIR="${BASE_DIR}/data"
LOG_FILE="${DATA_DIR}/launch.log"
PYTHON_STDOUT_FILE="${DATA_DIR}/python-stdout.log"
PYTHON_STDERR_FILE="${DATA_DIR}/python-stderr.log"

mkdir -p "$DATA_DIR"

export HOME="/root"
export XDG_CONFIG_HOME="/root/.config"
export RAOFFLINEPROXY_CONFIG_DIR="$DATA_DIR"
export RAOFFLINEPROXY_RETROARCH_CFG="/opt/muos/share/info/config/retroarch.cfg"
export RAOFFLINEPROXY_CACHE_IMAGES=0
export SDL_VIDEODRIVER="evdev"
export PYTHONPATH="${APP_DIR}:${BASE_DIR}"
export LD_LIBRARY_PATH="${PYGAME_LIBS_DIR}:/usr/lib/gl4es:/opt/muos/frontend/lib:/usr/lib"

cd "$BASE_DIR"

printf '%s %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "launch.sh args: $*" >>"$LOG_FILE"
printf '%s\n' "BASE_DIR=$BASE_DIR" >>"$LOG_FILE"
printf '%s\n' "SDL_VIDEODRIVER=$SDL_VIDEODRIVER" >>"$LOG_FILE"
printf '%s\n' "PYTHONPATH=$PYTHONPATH" >>"$LOG_FILE"
printf '%s\n' "LD_LIBRARY_PATH=$LD_LIBRARY_PATH" >>"$LOG_FILE"

if [ "$#" -eq 0 ]; then
    set -- menu-sdl
fi

case "$1" in
    menu-sdl)
        exec /usr/bin/python -m raofflineproxy.main "$@" >>"$PYTHON_STDOUT_FILE" 2>>"$PYTHON_STDERR_FILE"
        ;;
    *)
        exec /usr/bin/python -m raofflineproxy.main "$@"
        ;;
esac
