# RAOfflineProxy

## Architecture
- **ProxyServer** — raw TCP server on `127.0.0.1:8080`. Handles RetroArch's HTTP requests (rcheevos auto-prepends `http://` when `cheevos_custom_host` has no scheme).
- **AppDatabase** — Room DB with two tables: `api_cache` (GET/patch responses) and `pending_awards` (offline unlock queue).
- **AwardFlusher** — flushes pending awards to real RA server when connectivity returns.
- **ProxyService** — foreground service that owns the ProxyServer lifetime and a `ConnectivityManager` callback to trigger flushing.
- **RetroArchCfgPatcher** — writes `cheevos_custom_host = "127.0.0.1:8080"` into retroarch.cfg.
- **MainActivity / MainViewModel** — minimal control UI.

## Key config constraint
`cheevos_custom_host` in RetroArch's cfg lives at:
```
/data/user/0/com.retroarch.aarch64/files/retroarch.cfg
```
This path is **not writable** by a third-party app without root. The patcher tries readable fallback paths on shared storage first. If auto-patch fails, the user must set it manually via:
- `adb shell` (no root required for adb): `adb shell "sed -i 's/cheevos_custom_host = .*/cheevos_custom_host = \"127.0.0.1:8080\"/' /data/user/0/com.retroarch.aarch64/files/retroarch.cfg"`
- Or a root file manager.

## Opening in Android Studio
1. Open Android Studio → File → Open → select `~/src/RAOfflineProxy`
2. Let Gradle sync (it will download dependencies automatically)
3. Requires Android SDK API 34 installed

## minSdk
API 26 (Android 8.0) — covers >99% of active Android devices.
# RAOfflineProxy
