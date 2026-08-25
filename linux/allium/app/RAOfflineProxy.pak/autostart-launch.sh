#!/bin/sh
set -eu

appdir="$(cd "$(dirname "$0")" && pwd)"

. "$appdir/common.sh"

prepare_env

if ! resolve_python_bin; then
    exit 0
fi

PYTHON_BIN="$RESOLVED_PYTHON_BIN"

run_backend "$PYTHON_BIN" boot-reconcile >/dev/null 2>&1 || true
