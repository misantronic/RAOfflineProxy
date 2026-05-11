# Caveats

## Android 14+

RAOfflineProxy currently does not seem to work reliably on Android 14 and newer.

I still need to test this in more detail on those devices, but I do not currently own any devices running those Android versions.

## Important Shutdown Behavior

- Always stop sync before killing the app.
- On some devices, swiping the app away or crashing while the proxy is active does not reliably revert the patched emulator config immediately.
- If the app was killed or crashed during sync, reopen RAOfflineProxy once so it can restore the config on launch.
