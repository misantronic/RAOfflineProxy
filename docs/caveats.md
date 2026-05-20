# Caveats

## Android 14 on Anbernic and Mangmi

Reports so far suggest RAOfflineProxy is not working reliably on some Anbernic and Mangmi Android 14 devices, especially the RG477M and Mangmi Air X.

One reported blocker on Mangmi Air X is that it is still not possible to grant access to `Android/data/com.retroarch.aarch64/files`. The system folder picker shows **Use this folder** as greyed out, and trying to confirm it returns: `To protect your privacy, choose another folder`.

I currently do not own an Android 14 device from mentioned brand, so I cannot reproduce or debug this directly yet.

If someone wants to [donate one](/contact) for testing, I would be very glad to have it.

## Android 15+

RAOfflineProxy currently does not seem to work reliably on Android 15 and newer.

Virtual-device testing showed Android 14 can still work through SAF folder access.

On Android 15 and 16, the current blocker is the system folder picker: it does not allow selecting a folder under `Android/data`, so RAOfflineProxy cannot obtain access to `retroarch.cfg` or `RetroAchievements.ini` in supported emulator app storage.

## Important Shutdown Behavior

- Always stop sync before killing the app.
- On some devices, swiping the app away or crashing while the proxy is active does not reliably revert the patched emulator config immediately.
- If the app was killed or crashed during sync, reopen RAOfflineProxy once so it can restore the config on launch.

## Playtime

While you play offline, playtime cannot be tracked.

Achievements, unlock state, and queued softcore awards still work through the proxy, but RetroAchievements does not receive live session updates during offline play. That means any playtime normally recorded by RA will not be updated until you are back online, and offline playtime itself is not recovered later.
