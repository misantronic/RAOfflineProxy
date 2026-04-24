# Troubleshooting / FAQ

> Current release stage: alpha (`v1.0.0-alpha1`). Some rough edges are expected while the first public prerelease is being validated.

## The proxy starts but RetroArch still contacts RetroAchievements directly

**Cause:** RetroArch's config is not patched, or RetroArch loaded a config from a different location.

**Fix:**
1. Stop and restart the proxy — starting the proxy automatically patches RetroArch's config.
2. Restart RetroArch after starting the proxy — it only reads the custom server setting at startup.
3. If the app can't patch automatically, see [manual patching](./cfg-patching#manual-patching-adb-fallback).

---

## Achievements are not unlocking offline

**Cause 1:** The game is not cached.

**Fix:** You must cache the game while online before going offline. See [Caching Games](./caching-games).

**Cause 2:** The proxy service is not running.

**Fix:** Open RAOfflineProxy and tap **Start proxy** in the action bar.

**Cause 3:** Hardcore mode is enabled in RetroArch.

**Fix:** RAOfflineProxy does not support hardcore mode. Starting the proxy disables it automatically, but if it was re-enabled manually, achievements will be rejected. Disable hardcore mode in RetroArch → Settings → Achievements.

---

## Pending awards are not being sent when I reconnect

**Cause 1:** Authentication error — your RA token may need to be refreshed.

**Fix:** Open RetroArch → Settings → Achievements, log in again, then return to RAOfflineProxy. The next sync attempt should succeed.

**Cause 2:** The hash chain is broken.

**Fix:** A "Chain broken at index N" warning will appear in the Pending Awards screen. This means the local data was modified in a way that broke the integrity chain. You can:
- Use **Settings → Clear Database** to remove all pending awards and start fresh (awards will be lost)
- If you believe it is a bug, please report it on [GitHub Issues](https://github.com/misantronic/RAOfflineProxy/issues) or email [misantronic@posteo.se](mailto:misantronic@posteo.se)

**Cause 3:** An award has failed 5 or more times.

**Fix:** The award remains in the queue but is no longer retried automatically. Check the error shown on the award card. If it was a network error, it will retry again on the next app session.

---

## A dialog asks me to grant folder access when starting the proxy

**Cause:** On Android 12 and below, the app found `retroarch.cfg` but cannot write to it directly due to storage restrictions.

**Fix:** Tap **Grant** in the dialog and navigate to the folder that contains `retroarch.cfg` (usually inside the RetroArch data folder). Grant read + write access. The app will patch the config and start the proxy automatically.

---

## Why is there a `retroarch.raofflineproxy.cfg` file?

RAOfflineProxy creates this file as a one-time snapshot of `retroarch.cfg` the first time it starts patching and the backup is missing.

- It is created only if the file does not already exist
- It is not overwritten later
- It is not used automatically by the app

---

## A staging copy message appears asking me to manually copy a file

**Cause:** The app could not write the patched config back to its original location (last-resort fallback).

**Fix:** The patched file has been saved to a temporary location shown in the app. Copy it to the path shown using a file manager app or adb.

---

## The Cached Games list is empty after I scanned ROMs

**Cause:** No ROMs were matched to RetroAchievements games. This can happen if:
- The ROMs are not in the RA database
- The ROMs are in a format RA doesn't recognize (wrong dump, header stripped, etc.)
- You were offline during the scan

**Fix:** Make sure you are online and retry the scan. Try the **Add ROM** option to add individual files and check the progress message for specific errors.

---

## How do I know if my token is valid?

The **Home** screen shows an orange warning if your token is invalid. The app validates the token by making a live request to RA for one of your cached games while online.

If you have no cached games, the token is assumed valid.

---

## Does RAOfflineProxy work with all RetroArch cores?

RAOfflineProxy works at the network level — it is transparent to all RetroArch cores. Any core that uses RetroArch's built-in achievement system will work through the proxy automatically.

---

## What happens if I uninstall RAOfflineProxy without reverting the config?

RetroArch will keep trying to connect to the proxy, which will fail since nothing is listening. Achievement features in RetroArch will not work until you either:
- Reinstall RAOfflineProxy, start the proxy, then stop it (which reverts the config), or
- Manually clear the custom server setting in `retroarch.cfg`

---

## Is hardcore mode supported?

**No.** Hardcore mode is permanently unsupported. Any hardcore achievement unlock is immediately rejected. Starting the proxy also disables hardcore mode in RetroArch's config (and restores it when you stop the proxy). This is an intentional design decision — the integrity guarantees required for hardcore mode cannot be provided by a local proxy.

---

## The proxy service stops unexpectedly

**Cause:** Android's battery optimization may be killing the service.

**Fix:** Add RAOfflineProxy to the battery optimization whitelist:
- Android Settings → Apps → RAOfflineProxy → Battery → Unrestricted

If the app was killed or crashed while sync was active, reopen RAOfflineProxy once so it can clean up `retroarch.cfg`. For guaranteed immediate cleanup, always press **Stop proxy** before killing the app.

---

## Where is the data stored?

The app's database is stored in its private internal storage, which is not directly accessible without root. Use **Settings → Clear Cache** or **Settings → Clear Database** to manage it from within the app.

::: warning App updates may reset data
If you install an update that includes a database structure change, the database is automatically reset. You will need to re-cache your games.
:::

## Still need help?

Send feedback or support questions to [misantronic@posteo.se](mailto:misantronic@posteo.se), or see [Contact / Feedback](./contact).
