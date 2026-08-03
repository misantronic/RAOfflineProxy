#!/bin/bash

# Setting component name and path based on the directory name
component_name="$(basename "$(dirname "$(readlink -f "${BASH_SOURCE[0]}")")")"

if [[ "$action" == "reset" ]]; then # Run reset-only commands
  log i "----------------------"
  log i "Resetting $component_name"
  log i "----------------------"

  # raofflineproxy resolves its own config dir from XDG_CONFIG_HOME at
  # startup (falls back to ~/.config/raofflineproxy), so just make sure it
  # exists ahead of first launch.
  create_dir "$XDG_CONFIG_HOME/raofflineproxy"
fi
