# Caveats

## Android 15+

RAOfflineProxy currently does not seem to work reliably on Android 15 and newer.

Virtual-device testing showed Android 14 can still work through SAF folder access.

On Android 15 and 16, the current blocker is the system folder picker: it does not allow selecting a folder under `Android/data`, so RAOfflineProxy cannot obtain access to `retroarch.cfg` or `RetroAchievements.ini` in supported emulator app storage.

## Important Shutdown Behavior

- Always stop sync before killing the app.
- On some devices, swiping the app away or crashing while the proxy is active does not reliably revert the patched emulator config immediately.
- If the app was killed or crashed during sync, reopen RAOfflineProxy once so it can restore the config on launch.
