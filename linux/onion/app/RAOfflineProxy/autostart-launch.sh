#!/bin/sh
set -eu

. /mnt/SDCARD/App/RAOfflineProxy/common.sh

prepare_env

if ! resolve_python_bin; then
    exit 0
fi

PYTHON_BIN="$RESOLVED_PYTHON_BIN"

run_backend "$PYTHON_BIN" start-proxy >/dev/null 2>&1 || true
