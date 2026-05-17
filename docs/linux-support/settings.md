# Settings & Auto-start

Auto-start and shutdown behavior differ by target.

## Auto-start

:::tabs key:linux-target

== KNULLI

On KNULLI, autostart is available from the menu.

If you enable it, the proxy starts automatically through the KNULLI startup hook:

```text
/userdata/system/custom.sh
```

Persistent config and cache data live under the KNULLI/Linux config path, so the menu, manual launches, and autostarted proxy services after reboot all see the same state.

== Onion

Onion autostart is available from the Onion app menu.

If you enable it, RAOfflineProxy installs a startup script under:

```text
/.tmp_update/startup/raofflineproxy.sh
```

That startup script calls the app's headless launcher:

```text
/App/RAOfflineProxy/autostart-launch.sh
```

:::

## Start / Stop Behavior

Starting the proxy:

- Patches RetroArch
- Starts the background local proxy service
- Keeps the required RA settings active while the service is running

Stopping the proxy:

- Stops the service
- Reverts the RetroArch config patch

On Onion, the app also installs a shutdown cleanup hook under:

```text
/.tmp_update/checkoff/raofflineproxy.sh
```

That hook calls the app's cleanup helper so the proxy can stop and revert during orderly Onion shutdown.

This is best-effort cleanup. It helps with normal shutdown, but it is not a guarantee against crashes or hard power loss.
