#!/bin/bash

source /app/libexec/logger.sh

# Setting component name and path based on the directory name
component_name="$(basename "$(dirname "$(readlink -f "${BASH_SOURCE[0]}")")")"
component_path="$(cd "$(dirname "${BASH_SOURCE[0]}" )" && pwd)"

export LD_LIBRARY_PATH="$component_path/pyruntime/lib:${DEFAULT_LD_LIBRARY_PATH}"

log i "RetroDECK is now launching $component_name"
log d "Library path is: $LD_LIBRARY_PATH"
log d "AppDir is: $component_path"

# No arguments: run the proxy as a foreground service (RetroDECK is expected
# to manage this as a background process tied to its offline-mode setting).
# Any arguments: forward to the CLI as-is, e.g. "status", "start-proxy".
if [ "$#" -eq 0 ]; then
  exec "$component_path/pyruntime/bin/python3" -m raofflineproxy.main run-service
else
  exec "$component_path/pyruntime/bin/python3" -m raofflineproxy.main "$@"
fi
