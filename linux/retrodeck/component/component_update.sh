#!/bin/bash

#########################################################################
# These actions happen conditionally based on the version being upgraded
#########################################################################

if [[ $(check_version_is_older_than "$version_being_updated" "1.9.0-alpha1") == "true" ]]; then
  # 1.9.0-alpha1: initial RetroDECK component, no prior state to migrate.
  log i "1.9.0-alpha1 Upgrade - Reset: RAOfflineProxy"

  prepare_component "reset" "raofflineproxy"
fi
