#!/bin/sh
set -eu

APP_DIR=/mnt/SDCARD/App/RAOfflineProxy

if [ -x "$APP_DIR/autostart-launch.sh" ]; then
    sh "$APP_DIR/autostart-launch.sh"
fi
