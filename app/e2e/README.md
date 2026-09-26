# Android emulator E2E harness

Runs the real app on an Android emulator against the
[fake RA server](../../linux/tests/e2e/fake_ra/README.md) the Linux harness
uses. It needs no hardware, no RetroAchievements account and no real
emulator apps.

## Running

Start an emulator (API 30+) or attach a device, then run from the repo root:

```bash
python3 -m pytest app/e2e --android-e2e
```

`RAOP_ANDROID_E2E=1` works the same as the flag. Without either, the scenarios
skip, and only the harness self-tests in `test_harness.py` run.

The harness builds what it installs, unless the APKs are already there:

```bash
./gradlew :app:assembleE2e :e2e-stub-emulator:assembleRetroarchDebug :e2e-stub-emulator:assembleFlycastDebug
```

- `RAOP_ANDROID_E2E_REBUILD=1` forces a rebuild.
- `RAOP_ANDROID_E2E_APK=<path>` tests a prebuilt app APK instead.
- `ANDROID_SERIAL` picks a device when more than one is attached.

The emulator has to reach the internet so Android marks its network as
validated. The app treats an unvalidated network as offline.

## How it fits together

| Piece | What it does |
| --- | --- |
| `e2e` build type (`app/build.gradle.kts`) | debug build with `RA_HOST` and `RA_MEDIA_HOST` set to `http://10.0.2.2:8181` (the emulator's alias for the host loopback) and cleartext allowed to that address. Override with `-Pe2eRaHost=...` |
| `e2e-stub-emulator/` | two tiny APKs. `com.retroarch` only has to be installed for detection. `com.flycast.emulator` implements the host-override broadcast receiver and records the host it was sent to `files/host_override` |
| `harness/fake_ra_host.py` | the Linux fake RA server, run in-process on the host. Control is direct state access, so it keeps working while the device is in airplane mode |
| `harness/rcheevos_host.py` | the Linux rcheevos request replay, sent from the host through `adb forward tcp:18080 tcp:8080` |
| `harness/ui.py` | taps views by resource id from a `uiautomator dump`, so start and stop go through the real `MainViewModel` path |
| `harness/session.py` | per-test reset and the app-level helpers the scenarios use |

## Per-test reset

Each test clears the app's data and deletes the host the Flycast stub
recorded. It then grants
all-files access and the notification permission, and seeds
`ra_proxy_prefs.xml` with update checks and the smart-cache prompt turned off.
It writes a fresh `/storage/emulated/0/RetroArch/retroarch.cfg` holding the
fake server's `testuser` token, and swaps in a fresh fake RA state.

## Coverage

- **Start:** patches `retroarch.cfg` through the direct-file path and keeps a
  backup of the original. It turns hardcore off and sends the host-override
  broadcast.
- **Stop:** reverts the config, restores hardcore (or leaves it off when it was
  off before) and clears the host override.
- **Online:** the service reports online against the fake server. Launch
  traffic is forwarded with the `RAOfflineProxy/` user-agent tag. Unknown hashes
  resolve to no game, and online awards are forwarded straight away.
- **Hardcore:** a hardcore award is refused with a 403 and never reaches RA.
- **Offline:** airplane mode takes the device offline for real, so
  `ConnectivityManager` reports the network as lost. Cached reads are served
  with no upstream traffic. Awards are queued, signed and shown as pending in
  the notification, then flushed with a valid user agent once the network
  returns.

## What it cannot catch

- A real emulator app connecting through the proxy.
- SAF grants made through the system picker.
- Direct-file access on API 29 and below: there is no `WRITE_EXTERNAL_STORAGE`,
  so those versions always need a SAF grant.
- `Android/data` access rules.
- Vendor ROMs killing the service, and boot autostart.

Shizuku is out of scope on purpose, because it goes away once every emulator
supports host overrides.

## CI

The `android-e2e` job in `.github/workflows/tests.yml` runs one leg per API
level (30 and 35) on `reactivecircus/android-emulator-runner` with KVM. It runs
on pull requests that touch the app, the stub module or the shared fake RA
server, on PRs with the `e2e` label, and on `workflow_dispatch`.
