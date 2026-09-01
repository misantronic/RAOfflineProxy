# Container E2E harness

Runs the real device bundle, on the real target architecture, against the
[fake RA server](fake_ra/README.md) — no hardware, no network, no RA account.

## Running

Container scenarios are opt-in so the normal suite stays fast:

```bash
python3 -m pytest linux/tests/e2e --e2e-device knulli --e2e-device rocknix
```

`RAOP_E2E=1` runs every device; `RAOP_E2E_DEVICE=knulli,muos` selects some. With
no opt-in, `python3 -m pytest linux/tests/` skips them and finishes in seconds.

Requires Docker with binfmt for the target arch. On a fresh Linux host:

```bash
docker run --privileged --rm tonistiigi/binfmt --install arm64,arm
```

Seven devices are wired up: `knulli`, `rocknix`, `muos`, `darkos` (aarch64) and
`onion`, `spruce`, `allium` (armv7). The armv7 three are fully emulated and take
roughly 5-6 minutes each; the aarch64 four take about 2.

`darkos` is the one target whose container boots a real init: the proxy is a
systemd unit there, so the image runs `/sbin/init` under `--privileged` with a
writable cgroup mount instead of parking on `sleep infinity`. It is also the only
one installed and driven as a non-root account (`ark`), because dArkOS launches
Tools entries unprivileged and installing as root would hide the file-ownership
bugs that model causes.

`RAOP_E2E_INSTALLER=/path/to/installer.sh` skips the bundle build and tests a
prebuilt artifact instead.

## CI

The `linux-e2e` job in `.github/workflows/tests.yml` runs one matrix leg per
device. It fires nightly, on `workflow_dispatch`, and on a pull request only
when that PR carries the `e2e` label — the GitHub runners are x86_64, so every
device architecture is emulated there and the suites are much slower than on an
arm64 developer machine.

The job builds each bundle from source, so it needs the same prerequisites the
build scripts do: zig for the cross-compiled hasher (`ZIG_BIN=zig`), the fetched
pygame/CPython vendor assets, and an image resizer for the three bundles that
resize their launcher icon.

## Layout

| Path | Purpose |
| --- | --- |
| `devices.py` | the device matrix — paths, arch, bundle script, residue list |
| `harness/container.py` | thin `docker run/exec/cp` wrapper |
| `harness/bundle.py` | builds (or finds) the device installer |
| `harness/cli.py` | drives the app's CLI subcommands in-container |
| `harness/rcheevos.py` | replays the emulator's request sequence at the proxy |
| `harness/ractl_client.py` | fake RA control plane, over `docker exec` |
| `rootfs/` | per-device Dockerfiles and seed configs; `Dockerfile.miyoo` is shared by the three Miyoo firmwares via a `FIRMWARE` build-arg |
| `scenarios/` | the actual tests |

Adding a device is one `Device(...)` entry plus a Dockerfile. Devices that
share hardware share an image: `_miyoo()` in `devices.py` builds all three
Miyoo entries from one factory. Two `Device` flags cover targets that do not fit
the default shape: `needs_systemd=True` boots `/sbin/init` in the container, and
`run_as="ark"` installs and drives the app as that account rather than root.

## Design notes

**Rootfs fidelity.** Each `rootfs/Dockerfile.*` is a device-*shaped* rootfs, not
the real firmware image: the right architecture, an `/etc/os-release` the
platform detection recognises, the device's directory tree, and BusyBox applets
ahead of GNU coreutils on `PATH`. That last part matters — the installers'
`base64 -d`/`tar`/`awk` path is where a device-only truncation bug has bitten
before, and GNU coreutils would paper over it.

The base image's Python minor version is chosen per device. ROCKNIX is on
Debian trixie for CPython 3.13, because its bundle vendors pygame extension
modules per ABI tag and a mismatch is silent until the menu is launched on
hardware; Knulli and muOS are on bookworm for 3.11. The Miyoo firmwares ship
their own CPython 3.9 inside the bundle, so the base image's python only
matters as the fallback `resolve_python_bin` must *not* pick.

None of this catches anything tied to the real kernel, GPU, or vendor libraries.
Those still need hardware. To test against the genuine article, import a real
rootfs (`docker import`) and point the device's `dockerfile` at it.

**Config paths are resolved, not injected.** The app supports env overrides
(`RAOFFLINEPROXY_RETROARCH_CFG` and friends), and the harness deliberately does
not use them. `detect_retroarch_cfg()` and `running_on_*()` are the
device-specific functions most likely to break, so they run for real against the
filesystem.

**Offline is real.** `go_offline()` runs `ip link set eth0 down`, so
`has_active_network_interface()` genuinely returns `False` — no monkeypatching.
The fake RA server lives on loopback inside the container and its control plane
is driven through `docker exec`, so both keep working while the interface is
down. The service's `ConnectivityMonitor` polls every 15s, which is why the
offline tests wait on `online_state.json` rather than asserting immediately.

## Coverage

Every suite walks the same lifecycle: install, tree layout, platform detection,
the cross-compiled hasher loading and hashing on the target arch, start/stop
with config patch and revert, hardcore disabled and restored (including the
already-off pre-state), autostart on/off and `boot-reconcile`, manual cache and
clear, online launch caching, hardcore award refused and never forwarded, the
offline award queue-and-flush cycle, and offline reads from cache.

| device | arch | install shape | tests |
| --- | --- | --- | --- |
| knulli | aarch64 | self-extracting `.sh` | 19 |
| rocknix | aarch64 | self-extracting `.sh` | 20 |
| muos | aarch64 | `.muxapp` zip | 22 |
| onion | armv7 | SD-card zip | 21 |
| spruce | armv7 | SD-card zip | 23 |
| allium | armv7 | SD-card zip | 21 |

Device-specific coverage on top of the shared lifecycle:

**ROCKNIX** — patch and revert across four config files (`retroarch.cfg`,
`system.cfg`, `ppsspp.ini`, Dolphin's `RetroAchievements.ini`), each with its own
host and hardcore key and its own revert shape; `system.cfg` standing in for
`batocera.conf`; RA credentials read from `system.cfg`; the vendored pygame's ABI
tag matching the interpreter.

**muOS** — the `.muxapp` unzip path; `launch.sh` calling `/usr/bin/python`;
`launch.sh` exporting `RAOFFLINEPROXY_CONFIG_DIR` and `..._RETROARCH_CFG` itself
(the one place the harness does not fight an env override, because the shipping
product sets it); `detect_batocera_conf()` returning `None`; autostart writing
both `init/raofflineproxy.sh` and the `advanced/user_init` flag; and theme
icons actually being removed by the uninstaller (see below).

**Miyoo family** (`_miyoo_common.py`, subclassed per device) — the bundled armv7
CPython 3.9 being the interpreter `resolve_python_bin` actually selects rather
than the system one; `common.sh` as the product's own CLI dispatcher, since
`launch.sh` goes straight to the menu and there is no CLI entry point; no in-app
uninstaller, so removal is manual and the test asserts the card is left clean.

**Onion** — the v4.4.0 build gate, tested from both sides: an older
`onionVersion/version.txt` refuses to start and leaves the config untouched.

**spruce** — the proxy moving to port 8099 because spruce ships SFTPGo on 8080;
patching the per-platform `retroarch-MiyooMini.cfg` and *not* the legacy
`.retroarch/retroarch.cfg` that spruce never reads; `spruce_platform()`
resolution; the boot hook inserted into `.tmp_update/updater`; and the
Onion-over-spruce tie-break, where both markers are present and
`onionVersion/version.txt` decides.

**Allium** — the `.pak` layout rather than the shared `App/` tree; detection from
`.allium/`; the boot hook sharing spruce's `updater` path while dispatching on
`running_on_allium()`; and the hook skipping autostart while `allium-ota.zip` is
staged.

## Regression: BusyBox `find` has no `-delete`

`linux/muos/uninstall.sh` used to clear theme icons with:

```sh
find /run/muos/storage/theme -name "raofflineproxy.png" -delete 2>/dev/null || true
```

BusyBox `find` does not implement `-delete` — it exits 1 with
`find: unrecognized: -delete`, and `2>/dev/null || true` swallowed it, so the
icons survived uninstall on device while the script reported success. GNU `find`
deletes correctly, which is why host-side testing never caught it. The harness
found it on the first muOS run.

Fixed to the portable `-exec rm -f {} +`, which both implementations support, and
covered by `TestUninstall::test_uninstall_removes_the_theme_icon`.

The `find -delete` calls in the various `build_bundle.sh` scripts are fine —
those run on the build host, not the device.
