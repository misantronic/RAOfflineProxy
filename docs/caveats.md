# Caveats

## Important Shutdown Behavior

- Always stop sync before killing the app.
- On some devices, swiping the app away or crashing while the proxy is active does not reliably revert the patched emulator config immediately.
- If the app was killed or crashed during sync, reopen RAOfflineProxy once so it can restore the config on launch.
