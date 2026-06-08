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

# Install icon into every theme on each run (needed for Applications menu)
_install_icon() {
    ICON_SRC="${BASE_DIR}/raofflineproxy.png"
    [ -f "$ICON_SRC" ] || return 0
    find "/run/muos/storage/theme" \
        -path "*/glyph/muxapp" -type d 2>/dev/null | while read -r THEME_DIR; do
        cp "$ICON_SRC" "${THEME_DIR}/raofflineproxy.png"
    done
}
_install_icon

export HOME="/root"
export XDG_CONFIG_HOME="/root/.config"
export RAOFFLINEPROXY_CONFIG_DIR="$DATA_DIR"
export RAOFFLINEPROXY_RETROARCH_CFG="/opt/muos/share/info/config/retroarch.cfg"

# build_bundle.sh substitutes the placeholder with the bundle version so the
# running app (and its update check) reports the version it was packaged as.
# Left unsubstituted in the source tree, where config.py's default is used.
APP_VERSION="__RAOFFLINEPROXY_APP_VERSION__"
case "$APP_VERSION" in
    __RAOFFLINEPROXY_APP_VERSION__) ;;
    *) export RAOFFLINEPROXY_APP_VERSION="$APP_VERSION" ;;
esac

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
