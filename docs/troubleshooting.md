# Troubleshooting / FAQ

## The proxy starts but RetroArch still contacts retroachievements.org directly

**Cause:** `retroarch.cfg` is not patched, or RetroArch loaded a config from a different path.

**Fix:**
1. Open RAOfflineProxy → **RetroArch Setup** and check the patch status indicator.
2. If not patched, tap **Patch retroarch.cfg**.
3. Restart RetroArch after patching — it only reads `cheevos_custom_host` at startup.
4. If the app can't patch automatically, use the [manual adb method](./cfg-patching#manual-patching-adb-fallback).

---

## Achievements are not unlocking offline

**Cause 1:** The game is not cached.

**Fix:** You must cache the game while online before going offline. See [Caching Games](./caching-games).

**Cause 2:** The proxy service is not running.

**Fix:** Open RAOfflineProxy and tap **Start proxy** in the action bar.

**Cause 3:** Hardcore mode is enabled in RetroArch.

**Fix:** RAOfflineProxy does not support hardcore mode. The patcher sets `cheevos_hardcore_mode_enable = "false"`, but if it was re-enabled manually, achievements will be rejected. Disable hardcore mode in RetroArch → Settings → Achievements.

---

## Pending awards are not being flushed when I reconnect

**Cause 1:** Authentication error — your RA token has expired.

**Fix:** Open RetroArch → Settings → Achievements, log in again, then return to RAOfflineProxy. The next flush attempt should succeed.

**Cause 2:** The hash chain is broken.

**Fix:** A `Chain broken at index N` warning will appear in the Pending Awards screen. This means the database was modified in a way that broke the cryptographic chain. You can:
- Use **Settings → Clear Database** to remove all pending awards and start fresh (awards will be lost)
- If you believe it is a bug, please report it on [GitHub Issues](https://github.com/misantronic/RAOfflineProxy/issues)

**Cause 3:** Award has failed 5 or more times.

**Fix:** The award remains in the queue but is no longer retried automatically. Check the `lastError` shown on the award card. If it is a network error, it will retry again on the next app session.

---

## "Grant Folder Access" button appears after tapping Patch

**Cause:** On Android ≤ 12, the app found `retroarch.cfg` but cannot write to it directly due to scoped storage restrictions.

**Fix:** Tap **Grant Folder Access** and navigate to the folder that contains `retroarch.cfg` (usually `Android/data/com.retroarch.aarch64/files`). Grant read + write access. The app will re-patch automatically.

---

## A staging copy message appears asking me to manually copy a file

**Cause:** The app could not write the patched config back to its original location (last-resort staging fallback).

**Fix:** The patched file is at `/sdcard/RAOfflineProxy/retroarch.cfg`. Copy it to the path shown in the app. You can use a file manager app or adb:

```bash
adb push /sdcard/RAOfflineProxy/retroarch.cfg \
  /sdcard/Android/data/com.retroarch.aarch64/files/retroarch.cfg
```

---

## The Cached Games list is empty after I scanned ROMs

**Cause:** No ROMs were matched to RetroAchievements games. This can happen if:
- The ROMs are not in the RA database
- The ROMs are in a format RA doesn't recognize (wrong dump, header stripped, etc.)
- You were offline during the scan

**Fix:** Make sure you are online and retry the scan. Try the **Add ROM** option to add individual files and check the progress message for specific errors.

---

## How do I know if my token is valid?

The **Home** screen shows an orange warning if your token is invalid. The app validates the token by making a live `patch` request for one of your cached games when you open the Home screen while online.

If you have no cached games, the token is assumed valid (trusts the stored credentials).

---

## Does RAOfflineProxy work with all RetroArch cores?

RAOfflineProxy works at the network level — it is transparent to all RetroArch cores. Any core that uses RetroArch's built-in achievement system (rcheevos) will work through the proxy automatically.

---

## What happens if I uninstall RAOfflineProxy without reverting the cfg?

RetroArch will keep trying to connect to `127.0.0.1:8080`, which will fail (nothing is listening). Achievement features in RetroArch will not work until you either:
- Reinstall RAOfflineProxy and revert the cfg via the app, or
- Manually set `cheevos_custom_host = ""` in `retroarch.cfg`

---

## Is hardcore mode supported?

**No.** Hardcore mode (`h=1`) is permanently unsupported. Any award request with `h=1` is rejected by the proxy with HTTP 403. The patcher also disables hardcore mode in `retroarch.cfg`. This is an intentional design decision — the integrity guarantees required for hardcore mode cannot be provided by a local proxy.

---

## The proxy service stops unexpectedly

**Cause:** Android's battery optimization may be killing the service.

**Fix:** Add RAOfflineProxy to the battery optimization whitelist:
- Android Settings → Apps → RAOfflineProxy → Battery → Unrestricted

---

## Where is the database stored?

The Room database file is at:
```
/data/data/com.raofflineproxy/databases/raofflineproxy.db
```

It is an internal app file and is not directly accessible without root. Use **Settings → Clear Cache** or **Settings → Clear Database** to manage it from within the app.

::: warning Schema changes wipe data
If you install an update that includes a database schema change (version bump), the database is automatically wiped (`fallbackToDestructiveMigration`). You will need to re-cache your games.
:::
