#!/bin/sh
set -eu

appdir=/mnt/SDCARD/App/RAOfflineProxy

touch /tmp/stay_awake
cd "$appdir"

exec st -q -e sh "$appdir/onion-menu.sh"
