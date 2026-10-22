"""Windows E2E: the real portable Windows bundle, run from a temp folder,
driven through its CLI and an rcheevos-style client against the fake RA server.

There is no container on Windows, so this runs on the machine itself (in CI, a
throwaway windows-latest runner). Everything lives under a temp folder:
LOCALAPPDATA, APPDATA and USERPROFILE are pointed there for every process, the
emulator configs are test copies with the data folder pre-seeded so the real
emulators are never detected, and the proxy listens on its own port. The
autostart scenario uses the per-user Run entry (no admin prompt) and restores
whatever was there before.

Going offline is the fake server's offline mode (it drops connections), which
is what the proxy's reachability probe sees in a real outage.
"""

from __future__ import annotations

import json
import ctypes
import os
import platform
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from pathlib import Path

import pytest

from linux.tests.e2e.devices import REPO_ROOT
from linux.tests.e2e.harness.ractl_client import FakeRaControl
from linux.tests.e2e.harness.rcheevos import Emulator

pytestmark = pytest.mark.skipif(sys.platform != "win32", reason="Windows E2E runs on Windows")

USER = "testuser"
TOKEN = "tok-testuser-000000000001"
MSLUG_HASH = "b43c8b4ec999588c04dad79bb8bcc745"
MSLUG_GAME_ID = 1447
PROXY_PORT = 18080
FAKE_RA_PORT = 8181
FAKE_RA_CTL_PORT = 8182
WINDOWS_UA = "RetroArch/1.21.0 (Windows 11 x64) fceumm_libretro"
RUN_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"

RETROARCH_CFG = (
    'cheevos_enable = "true"\n'
    'cheevos_hardcore_mode_enable = "true"\n'
    'cheevos_custom_host = ""\n'
    f'cheevos_username = "{USER}"\n'
    f'cheevos_token = "{TOKEN}"\n'
    'video_fullscreen = "false"\n'
)
DOLPHIN_INI = "[Achievements]\r\nEnabled = True\r\nHardcoreEnabled = True\r\nUsername = testuser\r\n"


def cfg_value(content: str, key: str) -> str | None:
    for line in content.splitlines():
        if line.strip().startswith(key):
            return line.split("=", 1)[1].strip().strip('"')
    return None


# Local stand-ins for the container helpers ----------------------------------------------


class LocalFakeRaControl(FakeRaControl):
    def __init__(self) -> None:
        super().__init__(container=None)

    def install(self) -> None:
        pass

    def _call(self, method: str, route: str, payload: dict | None = None) -> dict:
        url = f"http://127.0.0.1:{FAKE_RA_CTL_PORT}{route}"
        data = json.dumps(payload or {}).encode() if method == "POST" else None
        request = urllib.request.Request(url, data=data, method=method, headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(request, timeout=10) as response:
            return json.loads(response.read().decode())


class LocalEmulator(Emulator):
    def __init__(self) -> None:
        super().__init__(container=None, port=PROXY_PORT, user_agent=WINDOWS_UA)

    def install(self) -> None:
        pass

    def request(self, body: str, user_agent: str | None = None) -> tuple:
        request = urllib.request.Request(
            f"http://127.0.0.1:{self.port}/dorequest.php",
            data=body.encode(),
            headers={"User-Agent": user_agent or self.user_agent, "Content-Type": "application/x-www-form-urlencoded"},
            method="POST",
        )
        try:
            with urllib.request.urlopen(request, timeout=15) as response:
                return response.status, json.loads(response.read().decode())
        except urllib.error.HTTPError as error:
            return error.code, json.loads(error.read().decode())


# The portable folder ---------------------------------------------------------------


class PortableFolder:
    """The bundle copied to a temp folder, as a user unzips it. Its data/ is
    pre-seeded as already set up on this machine, pointing at test copies of
    the emulator configs, so the first start never detects (and patches) the
    real emulators on the machine running the tests."""

    def __init__(self, root: Path, bundle: Path) -> None:
        self.root = root
        self.bundle = bundle
        self.local_appdata = root / "Local"
        self.appdata = root / "Roaming"
        self.home = root / "home"
        self.folder = root / "Games" / "RAOfflineProxy"
        self.data_dir = self.folder / "data"
        self.retroarch_cfg = root / "RetroArch" / "retroarch.cfg"
        self.dolphin_config = root / "Dolphin" / "User" / "Config"
        self.dolphin_ini = self.dolphin_config / "RetroAchievements.ini"
        self.rom = root / "roms" / "mslug.7z"
        self.env = {
            **os.environ,
            "LOCALAPPDATA": str(self.local_appdata),
            "APPDATA": str(self.appdata),
            "USERPROFILE": str(self.home),
            # The Run entry, not the logon task: no admin prompt during tests.
            "RAOFFLINEPROXY_AUTOSTART_MODE": "run",
        }
        self.ra = LocalFakeRaControl()
        self.emulator = LocalEmulator()

    def prepare(self) -> None:
        for folder in (self.local_appdata, self.appdata, self.home, self.dolphin_config, self.rom.parent):
            folder.mkdir(parents=True, exist_ok=True)
        shutil.copytree(self.bundle, self.folder)
        self.data_dir.mkdir(exist_ok=True)
        self.retroarch_cfg.parent.mkdir(parents=True, exist_ok=True)
        self.retroarch_cfg.write_bytes(RETROARCH_CFG.encode())
        self.dolphin_ini.write_bytes(DOLPHIN_INI.encode())
        shutil.copy(REPO_ROOT / "linux" / "tests" / "fixtures" / "mslug.7z", self.rom)
        self.write_config(
            {
                "upstream_host": f"http://127.0.0.1:{FAKE_RA_PORT}",
                "proxy_port": PROXY_PORT,
                "retroarch_cfg": str(self.retroarch_cfg),
                "dolphin_config_dir": str(self.dolphin_config),
                "rom_root": str(self.rom.parent),
                "emulators_found_on": platform.node(),
                "windows_emulators": {
                    "retroarch": {"exe": "test", "config": str(self.retroarch_cfg), "enabled": True},
                    "dolphin": {"exe": "test", "config": str(self.dolphin_config), "enabled": True},
                },
            }
        )

    def write_config(self, values: dict) -> None:
        path = self.data_dir / "config.json"
        data = json.loads(path.read_text(encoding="utf-8")) if path.exists() else {}
        data.update(values)
        path.write_text(json.dumps(data), encoding="utf-8")

    def python(self) -> str:
        return str(self.folder / "python" / "python.exe")

    def run_script(self, name: str, timeout: int = 120) -> subprocess.CompletedProcess:
        return subprocess.run(
            ["cmd.exe", "/c", str(self.folder / name)],
            env=self.env, capture_output=True, text=True, timeout=timeout, check=False,
        )

    def cli(self, *args: str, check: bool = False) -> subprocess.CompletedProcess:
        return subprocess.run(
            [self.python(), "-m", "raofflineproxy.main", *args],
            env=self.env, cwd=self.folder, capture_output=True, text=True, timeout=300, check=check,
        )

    def app_python(self, snippet: str) -> subprocess.CompletedProcess:
        return subprocess.run(
            [self.python(), "-c", snippet], env=self.env, cwd=self.folder,
            capture_output=True, text=True, timeout=120, check=True,
        )

    def processes(self) -> list[int]:
        result = self.app_python(
            "from pathlib import Path; from raofflineproxy.windows import process as w; "
            f"print(w.processes_from_folder(Path(r'{self.folder}'), exclude_self=True))"
        )
        return json.loads(result.stdout.strip())

    def proxy_running(self) -> bool:
        return "running: yes" in self.cli("service-status").stdout

    def count(self, command: str) -> int:
        return int(self.cli(command, check=True).stdout.strip().split()[-1])

    def online_state(self):
        try:
            return json.loads((self.data_dir / "online_state.json").read_text()).get("online")
        except (OSError, ValueError):
            return None

    def wait_for(self, predicate, timeout: float = 150.0) -> bool:
        deadline = time.time() + timeout
        while time.time() < deadline:
            if predicate():
                return True
            time.sleep(1.0)
        return False


def _port_open(port: int) -> bool:
    with socket.socket() as probe:
        probe.settimeout(0.5)
        return probe.connect_ex(("127.0.0.1", port)) == 0


@pytest.fixture(scope="session")
def windows_bundle() -> Path:
    prebuilt = os.environ.get("RAOP_E2E_INSTALLER")
    if prebuilt:
        return Path(prebuilt)
    bash = shutil.which("bash")
    if bash is None:
        pytest.skip("bash (Git Bash) is needed to build the Windows bundle")
    subprocess.run([bash, "linux/windows/fetch_vendor.sh"], cwd=REPO_ROOT, check=True, timeout=600)
    subprocess.run([bash, "linux/windows/build_bundle.sh"], cwd=REPO_ROOT, check=True, timeout=1200)
    return REPO_ROOT / "linux" / "windows" / "dist" / "RAOfflineProxy"


@pytest.fixture(scope="session")
def fake_ra():
    if _port_open(FAKE_RA_PORT) or _port_open(PROXY_PORT):
        pytest.skip(f"ports {FAKE_RA_PORT}/{PROXY_PORT} are in use")
    process = subprocess.Popen(
        [sys.executable, "-m", "linux.tests.e2e.fake_ra", "--host", "127.0.0.1",
         "--port", str(FAKE_RA_PORT), "--ctl-port", str(FAKE_RA_CTL_PORT)],
        cwd=REPO_ROOT,
    )
    control = LocalFakeRaControl()
    deadline = time.time() + 30
    while time.time() < deadline:
        try:
            if control.health().get("ok"):
                break
        except Exception:
            time.sleep(0.3)
    else:
        process.kill()
        raise RuntimeError("fake RA did not come up")
    yield control
    process.kill()


@pytest.fixture
def installed(windows_bundle, fake_ra):
    fake_ra.set_mode("online")
    fake_ra.reset()
    fake_ra.clear_journal()
    # resolve(): CI runners give TEMP as an 8.3 short path (C:\Users\RUNNER~1),
    # while the app reports the long one, so compare like with like.
    root = Path(tempfile.mkdtemp(prefix="raop-win-e2e-")).resolve()
    app = PortableFolder(root, windows_bundle)
    app.prepare()
    try:
        yield app
    finally:
        if Path(app.python()).exists():
            app.run_script("Stop RAOfflineProxy.cmd")
        for pid in app.processes() if Path(app.python()).exists() else []:
            subprocess.run(["taskkill", "/F", "/PID", str(pid)], capture_output=True)
        shutil.rmtree(root, ignore_errors=True)


@pytest.fixture(scope="session")
def original_run_value():
    """The machine's own Run entry, read once before any test touches it. A
    value pointing into a test folder is a leftover from an interrupted run,
    never something to put back."""
    import winreg

    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY) as key:
            value = winreg.QueryValueEx(key, "RAOfflineProxy")[0]
    except OSError:
        return None
    return None if "raop-win-e2e-" in value else value


# Every test: starting the portable app installs the sign-in check (a Run entry)
# in the real HKCU, so each one puts the machine's own entry back afterwards.
@pytest.fixture(autouse=True)
def run_key_restored(original_run_value):
    import winreg

    saved = original_run_value
    yield
    with winreg.CreateKey(winreg.HKEY_CURRENT_USER, RUN_KEY) as key:
        if saved is None:
            try:
                winreg.DeleteValue(key, "RAOfflineProxy")
            except OSError:
                pass
        else:
            winreg.SetValueEx(key, "RAOfflineProxy", 0, winreg.REG_SZ, saved)


# Scenarios ---------------------------------------------------------------------------


class TestPortableFolder:
    def test_bundle_has_the_portable_layout(self, installed):
        folder = installed.folder
        assert (folder / "python" / "python.exe").exists()
        assert (folder / "python" / "pythonw.exe").exists()
        assert (folder / "app" / "raofflineproxy" / "main.py").exists()
        assert (folder / "app" / "raofflineproxy" / "raproxy_rchash.dll").exists()
        assert (folder / "app" / "raofflineproxy" / "zstd.dll").exists()
        assert (folder / "app" / "pygame").is_dir()
        assert (folder / "Start RAOfflineProxy.cmd").exists()
        assert (folder / "Stop RAOfflineProxy.cmd").exists()
        assert (folder / "portable.txt").exists()
        assert not (folder / "testing.json").exists(), "a release bundle must not be a test build"

    def test_data_lives_in_the_folder(self, installed):
        result = installed.app_python("from raofflineproxy import config; print(config.CONFIG_DIR)")
        assert result.stdout.strip() == str(installed.data_dir)

    def test_native_hasher_loads_and_hashes(self, installed):
        result = installed.app_python(
            "from pathlib import Path; from raofflineproxy import rom_hashing; "
            "assert rom_hashing.load_rchash() is not None; "
            f"print(rom_hashing.hash_rom(Path(r'{installed.rom}')))"
        )
        assert result.stdout.strip() == MSLUG_HASH

    def test_native_codecs_load(self, installed):
        installed.app_python("from raofflineproxy import native_codecs as n; n._zstd(); n._crypto()")


class TestProxyLifecycle:
    def test_start_proxy_patches_configs_and_runs(self, installed):
        result = installed.cli("start-proxy", check=True)
        assert "Patched retroarch.cfg" in result.stdout
        assert "running: yes" in installed.cli("service-status").stdout

        cfg = installed.retroarch_cfg.read_text()
        assert cfg_value(cfg, "cheevos_custom_host") == f"127.0.0.1:{PROXY_PORT}"
        assert cfg_value(cfg, "cheevos_hardcore_mode_enable") == "false"
        assert f"HostUrl = 127.0.0.1:{PROXY_PORT}" in installed.dolphin_ini.read_text()

    def test_stop_proxy_restores_configs_byte_for_byte(self, installed):
        retroarch_before = installed.retroarch_cfg.read_bytes()
        dolphin_before = installed.dolphin_ini.read_bytes()
        installed.cli("start-proxy", check=True)
        result = installed.cli("stop-proxy", check=True)
        assert "Reverted retroarch.cfg" in result.stdout
        assert "running: yes" not in installed.cli("service-status").stdout
        # LF RetroArch config and CRLF Dolphin ini both come back unchanged.
        assert installed.retroarch_cfg.read_bytes() == retroarch_before
        assert installed.dolphin_ini.read_bytes() == dolphin_before

    def test_stop_is_graceful(self, installed):
        installed.cli("start-proxy", check=True)
        started = time.time()
        installed.cli("stop-proxy", check=True)
        # Windows has no SIGTERM: the service stops on its stop-request file,
        # well inside the 10 second TerminateProcess fallback.
        assert time.time() - started < 8
        assert not (installed.data_dir / "service.stop").exists()

    def test_second_start_reuses_the_running_service(self, installed):
        installed.cli("start-proxy", check=True)
        first = json.loads((installed.data_dir / "service_status.json").read_text())["pid"]
        installed.cli("start-proxy", check=True)
        second = json.loads((installed.data_dir / "service_status.json").read_text())["pid"]
        # Windows finds the running service through its pid file (no /proc or ps).
        assert first == second


class TestEmulatorSelection:
    def test_a_turned_off_emulator_is_left_alone(self, installed):
        installed.app_python(
            "from pathlib import Path; from raofflineproxy import config; from raofflineproxy.windows import emulators as w; "
            "c = config.load_config(); "
            "c.setdefault('windows_emulators', {})['dolphin'] = "
            "{'exe': 'x', 'config': c['dolphin_config_dir'], 'enabled': False}; "
            "w.apply_state(c); config.save_config(c)"
        )
        before = installed.dolphin_ini.read_bytes()
        installed.cli("start-proxy", check=True)
        assert installed.dolphin_ini.read_bytes() == before
        assert f"127.0.0.1:{PROXY_PORT}" in installed.retroarch_cfg.read_text()


    def test_turning_retroarch_off_still_starts_and_patches_the_rest(self, installed):
        installed.app_python(
            "from raofflineproxy import config; from raofflineproxy.windows import emulators as w; "
            "c = config.load_config(); "
            "c.setdefault('windows_emulators', {})['retroarch'] = "
            "{'exe': 'x', 'config': c['retroarch_cfg'], 'enabled': False}; "
            "w.apply_state(c); config.save_config(c)"
        )
        before = installed.retroarch_cfg.read_bytes()
        result = installed.cli("start-proxy")
        assert result.returncode == 0, result.stdout + result.stderr
        assert "running: yes" in installed.cli("service-status").stdout
        assert installed.retroarch_cfg.read_bytes() == before
        assert f"HostUrl = 127.0.0.1:{PROXY_PORT}" in installed.dolphin_ini.read_text()
        installed.cli("stop-proxy", check=True)
        assert installed.retroarch_cfg.read_bytes() == before


    def test_a_pc_without_retroarch_still_starts_and_patches_the_rest(self, installed):
        installed.app_python(
            "from raofflineproxy import config; "
            "c = config.load_config(); c.pop('retroarch_cfg', None); c.pop('windows_emulators', None); "
            "config.save_config(c)"
        )
        before = installed.retroarch_cfg.read_bytes()
        result = installed.cli("start-proxy")
        assert result.returncode == 0, result.stdout + result.stderr
        assert "running: yes" in installed.cli("service-status").stdout
        assert installed.retroarch_cfg.read_bytes() == before
        assert f"HostUrl = 127.0.0.1:{PROXY_PORT}" in installed.dolphin_ini.read_text()


class TestServiceIdentity:
    def test_service_identity_is_recorded_with_its_creation_time(self, installed):
        installed.cli("start-proxy", check=True)
        identity = json.loads((installed.data_dir / "service.identity.json").read_text())
        status = json.loads((installed.data_dir / "service_status.json").read_text())
        assert identity["pid"] == status["pid"]
        assert isinstance(identity["created"], int) and identity["created"] > 0

    def test_a_stale_pid_owned_by_another_process_is_not_the_service(self, installed):
        installed.cli("start-proxy", check=True)
        installed.cli("stop-proxy", check=True)
        # Point the pid file at a live Python process that isn't the service, as
        # happens when Windows reuses a dead service's pid.
        impostor = subprocess.Popen([installed.python(), "-c", "import time; time.sleep(60)"])
        try:
            (installed.data_dir / "service.pid").write_text(str(impostor.pid))
            result = installed.cli("start-proxy", check=True)
            assert "already running" not in (result.stdout + result.stderr).lower()
            status = json.loads((installed.data_dir / "service_status.json").read_text())
            assert status["pid"] != impostor.pid
            assert impostor.poll() is None, "the unrelated process was killed"
        finally:
            impostor.kill()


class TestCaching:
    def test_cache_rom_resolves_hash_through_to_the_server(self, installed):
        installed.cli("cache-rom", "--path", str(installed.rom), check=True)
        assert installed.count("cached-games-count") == 1

    def test_launching_a_game_online_caches_it(self, installed):
        installed.cli("start-proxy", check=True)
        responses = installed.emulator.boot_sequence(USER, TOKEN, MSLUG_HASH)
        assert responses["gameid"]["GameID"] == MSLUG_GAME_ID
        assert installed.count("cached-games-count") == 1


class TestHardcoreIsRefused:
    def test_hardcore_award_is_rejected_and_never_forwarded(self, installed):
        installed.cli("start-proxy", check=True)
        installed.emulator.boot_sequence(USER, TOKEN, MSLUG_HASH)
        installed.ra.clear_journal()
        status, payload = installed.emulator.award(USER, TOKEN, 22001, hardcore=1)
        assert status == 403
        assert payload["Error"] == "hardcore_not_supported"
        assert installed.ra.violations() == []


class TestOfflineAwardCycle:
    def test_offline_award_queues_and_flushes_when_back_online(self, installed):
        installed.cli("start-proxy", check=True)
        installed.emulator.boot_sequence(USER, TOKEN, MSLUG_HASH)
        status, payload = installed.emulator.award(USER, TOKEN, 22001)
        assert status == 200 and payload["Success"] is True

        installed.ra.set_mode("offline")
        assert installed.wait_for(lambda: installed.online_state() is False), "service never noticed the outage"

        status, payload = installed.emulator.award(USER, TOKEN, 22002)
        assert payload["Success"] is True
        assert payload.get("Error") == "queued_offline"
        assert installed.count("pending-awards-count") == 1

        installed.ra.set_mode("online")
        assert installed.wait_for(lambda: installed.online_state() is True), "service never saw the server return"
        assert installed.wait_for(lambda: installed.count("pending-awards-count") == 0), "queued award never flushed"
        assert 22002 in installed.ra.unlocks(USER, MSLUG_GAME_ID)
        assert installed.ra.violations() == []

        # Windows keeps its own Awards History of what synced.
        history = json.loads((installed.data_dir / "awards_history.json").read_text())
        assert [entry["achievementId"] for entry in history] == [22002]


def exclusive_lock(path: Path) -> int:
    """Opens a file sharing nothing, as an antivirus scan can: renaming the
    folder that holds it fails until it's closed."""
    kernel32 = ctypes.windll.kernel32
    kernel32.CreateFileW.restype = ctypes.c_void_p
    handle = kernel32.CreateFileW(str(path), 0x80000000, 0, None, 3, 0x80, None)  # GENERIC_READ, no sharing
    assert handle not in (None, ctypes.c_void_p(-1).value), "couldn't lock the file"
    return handle


def launch_without_menu(folder: PortableFolder) -> None:
    """What sign-in does: start the proxy and tray, no menu window."""
    subprocess.Popen(
        [str(folder.folder / "python" / "pythonw.exe"), "-m", "raofflineproxy.windows.launch", "--autostart"],
        env=folder.env, cwd=folder.folder,
    )


class TestStartAndStop:
    def test_start_then_stop_leaves_nothing_running(self, installed):
        retroarch_before = installed.retroarch_cfg.read_bytes()
        launch_without_menu(installed)
        assert installed.wait_for(installed.proxy_running, timeout=60), "start didn't start the proxy"
        assert f"127.0.0.1:{PROXY_PORT}" in installed.retroarch_cfg.read_text()

        result = installed.run_script("Stop RAOfflineProxy.cmd")
        assert result.returncode == 0, result.stdout + result.stderr
        assert not installed.proxy_running()
        assert installed.retroarch_cfg.read_bytes() == retroarch_before
        assert installed.wait_for(lambda: installed.processes() == [], timeout=20), "something from the folder kept running"
    # Not covered here: a folder copied from another PC re-runs detection, and
    # detection scans this machine's real drives, so it would find and patch
    # the real emulators of whoever runs the suite.


class TestAutostart:
    def _location(self, installed) -> Path:
        return installed.local_appdata / "RAOfflineProxy-autostart" / "location.json"

    def _run_launcher(self, installed) -> None:
        launcher = installed.local_appdata / "RAOfflineProxy-autostart" / "start.ps1"
        subprocess.run(
            ["powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(launcher)],
            env=installed.env, capture_output=True, text=True, timeout=60, check=True,
        )

    def test_enabling_registers_the_launcher_and_tags_the_folder(self, installed, run_key_restored):
        import winreg

        installed.cli("enable-autostart", check=True)
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY) as key:
            value = winreg.QueryValueEx(key, "RAOfflineProxy")[0]
        assert "start.ps1" in value and "conhost.exe" in value
        location = json.loads(self._location(installed).read_text())
        assert Path(location["root"]) == installed.folder
        assert f"id={location['id']}" in (installed.folder / "portable.txt").read_text()
        assert installed.cli("autostart-status").stdout.strip() == "enabled"

    def test_the_launcher_finds_the_folder_after_a_drive_letter_change(self, installed, run_key_restored):
        installed.cli("enable-autostart", check=True)
        location = json.loads(self._location(installed).read_text())
        # As if the stick was last seen as Q: and is back under another letter:
        # only the last-known root is wrong, the path within the drive is the same.
        location["root"] = "Q:\\" + location["relative"]
        self._location(installed).write_text(json.dumps(location))

        self._run_launcher(installed)
        assert installed.wait_for(installed.proxy_running, timeout=90), "the launcher didn't find the folder"

    def test_the_launcher_does_nothing_when_the_folder_is_gone(self, installed, run_key_restored):
        installed.cli("enable-autostart", check=True)
        location = json.loads(self._location(installed).read_text())
        location["root"] = "Q:\\nowhere"
        location["relative"] = "nowhere\\RAOfflineProxy"
        self._location(installed).write_text(json.dumps(location))

        self._run_launcher(installed)
        time.sleep(5)
        assert not installed.proxy_running()

    def test_the_launcher_ignores_a_copy_on_another_drive(self, installed, run_key_restored):
        """Same path, same id, but a different drive (volume serial): a backup
        copy, which must not be started in the stick's place."""
        installed.cli("enable-autostart", check=True)
        location = json.loads(self._location(installed).read_text())
        assert location["volume"], "the drive's volume serial wasn't recorded"
        location["root"] = "Q:\\" + location["relative"]
        location["volume"] = "00000000"
        self._location(installed).write_text(json.dumps(location))

        self._run_launcher(installed)
        time.sleep(5)
        assert not installed.proxy_running()

    def _sign_in_check_installed_and_off(self, installed) -> None:
        import winreg

        installed.cli("enable-autostart", check=True)
        installed.cli("disable-autostart", check=True)
        assert installed.cli("autostart-status").stdout.strip() != "enabled"
        # Off, the sign-in check stays: it puts the emulators back after a crash.
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY) as key:
            assert "start.ps1" in winreg.QueryValueEx(key, "RAOfflineProxy")[0]

    def test_turned_off_sign_in_leaves_the_proxy_stopped(self, installed, run_key_restored):
        self._sign_in_check_installed_and_off(installed)
        self._run_launcher(installed)
        time.sleep(5)
        assert not installed.proxy_running()

    def test_sign_in_starts_the_proxy_again_after_a_crash(self, installed, run_key_restored):
        """Off, but the proxy was running when Windows went down: as on Android,
        it's started again, rather than leaving the emulators pointed at nothing."""
        self._sign_in_check_installed_and_off(installed)
        installed.cli("start-proxy", check=True)
        pid = json.loads((installed.data_dir / "service_status.json").read_text())["pid"]
        subprocess.run(["taskkill", "/F", "/PID", str(pid)], capture_output=True, check=True)
        assert installed.wait_for(lambda: not installed.proxy_running(), timeout=30)

        self._run_launcher(installed)
        assert installed.wait_for(installed.proxy_running, timeout=90), "not started again after the crash"

    def test_sign_in_after_a_deliberate_stop_restores_instead(self, installed, run_key_restored):
        self._sign_in_check_installed_and_off(installed)
        installed.cli("start-proxy", check=True)
        installed.cli("stop-proxy", check=True)

        self._run_launcher(installed)
        time.sleep(5)
        assert not installed.proxy_running()


class TestUpdate:
    def test_a_locked_file_leaves_the_old_version_working(self, installed, tmp_path):
        """A file held open (e.g. by antivirus) blocks the swap: the update must
        give up with the old version intact and running, not half deleted."""
        base, server = self._serve_release(installed, tmp_path)
        installed.cli("start-proxy", check=True)
        locked = exclusive_lock(installed.folder / "python" / "LICENSE.txt")
        try:
            installed.app_python(
                "from raofflineproxy.windows import update as w; i = w.check(force=True); "
                "w.download_and_launch_installer(i.asset_url, open_menu=False, checksum_url=i.checksum_url)"
            )
            log = installed.data_dir / "update.log"
            # Held until the updater has finished: it retries the rename while
            # locks clear, and would rightly succeed if this let go early.
            assert installed.wait_for(
                lambda: log.exists() and "transcript end" in log.read_text(errors="replace"), timeout=120
            ), "the updater never finished"
        finally:
            ctypes.windll.kernel32.CloseHandle(locked)
            server.shutdown()
        assert installed.wait_for(installed.proxy_running, timeout=60), "the old version wasn't restarted"
        version = installed.app_python("from raofflineproxy import config; print(config.APP_VERSION)").stdout
        assert "alpha99" not in version
        assert not (installed.folder / "python.old").exists() and not (installed.folder / "app.old").exists()

    def _serve_release(self, installed, tmp_path, checksum=None):
        """A newer release (the same bundle, stamped 2.2.0-alpha99) on a local
        server. checksum: None (no .sha256 asset), "good" or "bad"."""
        import functools
        import hashlib
        import http.server
        import threading

        release = tmp_path / "release"
        shutil.copytree(installed.bundle, release / "RAOfflineProxy")
        config_py = release / "RAOfflineProxy" / "app" / "raofflineproxy" / "config.py"
        config_py.write_text(
            config_py.read_text(encoding="utf-8").replace('or "2.2.0-alpha1"', 'or "2.2.0-alpha99"'),
            encoding="utf-8",
        )
        shutil.make_archive(str(tmp_path / "RAOfflineProxy-Windows-v2.2.0-alpha99"), "zip", release)
        handler = functools.partial(http.server.SimpleHTTPRequestHandler, directory=str(tmp_path))
        server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), handler)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        base = f"http://127.0.0.1:{server.server_port}"
        name = "RAOfflineProxy-Windows-v2.2.0-alpha99.zip"
        assets = [{"name": name, "browser_download_url": f"{base}/{name}"}]
        if checksum is not None:
            digest = hashlib.sha256((tmp_path / name).read_bytes()).hexdigest()
            if checksum == "bad":
                digest = "0" * 64
            (tmp_path / f"{name}.sha256").write_text(f"{digest}  {name}\n")
            assets.append({"name": f"{name}.sha256", "browser_download_url": f"{base}/{name}.sha256"})
        (tmp_path / "releases.json").write_text(json.dumps([{
            "tag_name": "v2.2.0-alpha99", "draft": False, "html_url": base, "assets": assets,
        }]))
        installed.write_config({"update_releases_url": f"{base}/releases.json"})
        return base, server

    def test_an_update_that_fails_its_checksum_is_not_installed(self, installed, tmp_path):
        _, server = self._serve_release(installed, tmp_path, checksum="bad")
        try:
            result = installed.app_python(
                "from raofflineproxy.windows import update as w; i = w.check(force=True); assert i.checksum_url, i\n"
                "try:\n    w.download_and_launch_installer(i.asset_url, open_menu=False, checksum_url=i.checksum_url)\n"
                "except RuntimeError as exc:\n    print('refused:', exc)"
            )
        finally:
            server.shutdown()
        assert "refused: the downloaded update doesn't match its published checksum" in result.stdout
        assert not (installed.data_dir / "update.log").exists()

    def test_an_update_replaces_the_program_and_keeps_the_data(self, installed, tmp_path):
        base, server = self._serve_release(installed, tmp_path, checksum="good")
        installed.cli("start-proxy", check=True)

        try:
            installed.app_python(
                "from raofflineproxy.windows import update as w; i = w.check(force=True); "
                "assert i.available, i; w.download_and_launch_installer(i.asset_url, open_menu=False, checksum_url=i.checksum_url)"
            )

            def updated() -> bool:
                try:
                    return "alpha99" in installed.app_python(
                        "from raofflineproxy import config; print(config.APP_VERSION)"
                    ).stdout
                except (subprocess.CalledProcessError, OSError):
                    return False  # mid-swap: python.exe is briefly absent

            def logs() -> str:
                parts = []
                for name in ("update.log", "service.log", "service_status.json"):
                    path = installed.data_dir / name
                    if path.exists():
                        parts.append(f"--- {name}\n" + path.read_text(errors="replace")[-3000:])
                return "\n".join(parts)

            assert installed.wait_for(updated, timeout=180), "update never installed\n" + logs()
            assert installed.wait_for(installed.proxy_running, timeout=60), "not restarted after the update\n" + logs()
            config = json.loads((installed.data_dir / "config.json").read_text())
            assert config["proxy_port"] == PROXY_PORT, "data/ was not kept"
            # The saved check offered alpha99, which is now running: no longer an update.
            offered = installed.app_python(
                "from raofflineproxy.windows import update as w; print(w.check().available)"
            ).stdout.strip()
            assert offered == "False", "still offering the version just installed"
        finally:
            server.shutdown()
