#!/bin/bash

# Setting component name and path based on the directory name
component_name="$(basename "$(dirname "$(readlink -f "${BASH_SOURCE[0]}")")")"

if [[ "$action" == "reset" ]]; then # Run reset-only commands
  log i "----------------------"
  log i "Resetting $component_name"
  log i "----------------------"

  # raofflineproxy resolves its config dir from XDG_CONFIG_HOME and its API
  # response cache from XDG_CACHE_HOME at startup (falling back to
  # ~/.config/raofflineproxy and ~/.cache/raofflineproxy), so just make sure
  # both exist ahead of first launch.
  create_dir "$XDG_CONFIG_HOME/raofflineproxy"
  create_dir "$XDG_CACHE_HOME/raofflineproxy"
fi
