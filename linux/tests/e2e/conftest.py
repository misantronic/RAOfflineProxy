from __future__ import annotations

import os
import time
from pathlib import Path

import pytest

from linux.tests.e2e.devices import DEVICES, E2E_DIR, REPO_ROOT
from linux.tests.e2e.harness.bundle import build_bundle
from linux.tests.e2e.harness.container import (
    Container,
    build_image,
    docker_available,
)
from linux.tests.e2e.harness.ractl_client import FakeRaControl
from linux.tests.e2e.harness.rcheevos import Emulator

FAKE_RA_PORT = 8181
FAKE_RA_CTL_PORT = 8182


def pytest_addoption(parser) -> None:
    parser.addoption(
        "--e2e-device",
        action="append",
        default=[],
        help="run container E2E scenarios for these devices (repeatable)",
    )


def _requested_devices(config) -> list:
    requested = list(config.getoption("--e2e-device"))
    if requested:
        return requested
    from_env = os.environ.get("RAOP_E2E_DEVICE", "")
    if from_env:
        return [name.strip() for name in from_env.split(",") if name.strip()]
    if os.environ.get("RAOP_E2E") == "1":
        return list(DEVICES)
    return []


def pytest_collection_modifyitems(config, items) -> None:
    requested = _requested_devices(config)
    docker_ok = docker_available() if requested else False

    opt_in = pytest.mark.skip(
        reason="container E2E is opt-in: pass --e2e-device <name> or set RAOP_E2E=1"
    )
    no_docker = pytest.mark.skip(reason="docker is not available")

    for item in items:
        if "scenarios" not in str(item.fspath):
            continue
        if not requested:
            item.add_marker(opt_in)
            continue
        if not docker_ok:
            item.add_marker(no_docker)
            continue
        module = Path(str(item.fspath)).stem
        if not any(name in module for name in requested):
            item.add_marker(pytest.mark.skip(reason="not in --e2e-device selection"))


class DeviceSession:
    def __init__(self, container, device, control, emulator) -> None:
        self.container = container
        self.device = device
        self.ra = control
        self.emulator = emulator
        self.cli = None

    def install(self):
        if self.device.install_mode == "sdcard-zip":
            result = self.container.exec(
                "unzip -q -o /tmp/RAOfflineProxy-Install.sh -d %s"
                % self.device.install_dest,
                check=True,
                timeout=600,
            )
            self.container.exec(
                "chmod +x %s/*.sh" % self.device.base_dir, check=True
            )
        elif self.device.install_mode == "muxapp":
            result = self.container.exec(
                "unzip -q -o /tmp/RAOfflineProxy-Install.sh -d %s"
                % self.device.install_dest,
                check=True,
                timeout=300,
            )
            self.container.exec(
                "chmod +x %s/launch.sh %s/uninstall.sh %s/mux_launch.sh"
                % ((self.device.base_dir,) * 3),
                check=True,
            )
        else:
            result = self.container.exec(
                "bash /tmp/RAOfflineProxy-Install.sh",
                check=True,
                timeout=300,
                user=self.device.run_as,
            )
        from linux.tests.e2e.harness.cli import AppCli

        self.cli = AppCli(self.container, self.device)
        self.point_at_fake_ra()
        return result

    def uninstall(self):
        result = self.container.exec(
            self.device.uninstall_path, timeout=300, user=self.device.run_as
        )
        # Some uninstallers detach the final rm -rf so they can delete the tree
        # they are running from; wait for that to land before asserting.
        self.container.exec("sleep 5")
        return result

    def point_at_fake_ra(self) -> None:
        config_path = self.device.config_dir + "/config.json"
        self.container.exec(
            "mkdir -p %s" % self.device.config_dir,
            check=True,
            user=self.device.run_as,
        )
        existing = "{}"
        if self.container.exists(config_path):
            existing = self.container.read_file(config_path)
        self.container.exec(
            "python3 - <<'RAOP_PY'\n"
            "import json\n"
            "data = json.loads(%r)\n"
            "data['upstream_host'] = 'http://127.0.0.1:%d'\n"
            "open(%r, 'w').write(json.dumps(data))\n"
            "RAOP_PY" % (existing, FAKE_RA_PORT, config_path),
            check=True,
            user=self.device.run_as,
        )

    def stage_rom(self, fixture: str) -> str:
        target = "%s/%s" % (self.device.rom_dir, fixture)
        self.container.exec(
            "mkdir -p %s" % self.device.rom_dir, check=True, user=self.device.run_as
        )
        self.container.exec(
            "cp /repo/linux/tests/fixtures/%s %s" % (fixture, target),
            check=True,
            user=self.device.run_as,
        )
        return target

    def app_python(self, snippet: str, check: bool = False):
        return self.container.exec(
            "cd %s/app && PYTHONPATH=%s/app:%s python3 -c %s"
            % (
                self.device.base_dir,
                self.device.base_dir,
                self.device.base_dir,
                _quote(snippet),
            ),
            check=check,
            user=self.device.run_as,
        )

    def go_offline(self) -> None:
        self.container.exec("ip link set eth0 down", check=True)

    def go_online(self) -> None:
        self.container.exec("ip link set eth0 up", check=True)
        time.sleep(0.5)

    def online_state(self):
        path = self.device.config_dir + "/online_state.json"
        if not self.container.exists(path):
            return None
        try:
            return self.container.json_from(path).get("online")
        except Exception:
            return None

    # ConnectivityMonitor polls every 15s and each probe is itself emulated, so
    # this needs headroom on a slow runner. It returns as soon as the state
    # flips, so a generous ceiling costs nothing on the passing path.
    def wait_for_online(self, expected: bool, timeout: float = 150.0) -> bool:
        deadline = time.time() + timeout
        while time.time() < deadline:
            if self.online_state() is expected:
                return True
            time.sleep(1.0)
        return False

    def wait_for_pending(self, expected: int, timeout: float = 150.0) -> bool:
        deadline = time.time() + timeout
        while time.time() < deadline:
            if self.cli.pending_award_count() == expected:
                return True
            time.sleep(1.0)
        return False


def _quote(value: str) -> str:
    return "'" + value.replace("'", "'\\''") + "'"


def _start_fake_ra(container, control) -> None:
    container.exec(
        "cd /repo && nohup python3 -m linux.tests.e2e.fake_ra "
        "--host 0.0.0.0 --port %d --ctl-port %d > /tmp/fake-ra.log 2>&1 &"
        % (FAKE_RA_PORT, FAKE_RA_CTL_PORT),
        check=True,
    )
    deadline = time.time() + 30
    while time.time() < deadline:
        try:
            if control.health().get("ok"):
                return
        except Exception:
            pass
        time.sleep(0.3)
    raise RuntimeError(
        "fake RA did not come up:\n" + container.exec("cat /tmp/fake-ra.log").stdout
    )


def _probe_systemd(device, tag: str) -> None:
    """Boot one throwaway container to prove PID 1 comes up.

    The session fixture builds a container per test, so without this a systemd
    that never settles costs every test its full timeout and the run looks like
    a silent hang. Failing here reports it once, with diagnostics.
    """
    probe = Container(
        image=tag,
        platform=device.platform,
        privileged=True,
        tmpfs=("/run", "/run/lock"),
        command=("/sbin/init",),
    )
    probe.start()
    try:
        probe.wait_for_systemd()
    finally:
        probe.stop()


def _image_fixture(device_name: str):
    @pytest.fixture(scope="session")
    def _image() -> str:
        if not docker_available():
            pytest.skip("docker is not available")
        device = DEVICES[device_name]
        tag = build_image(
            device.dockerfile,
            E2E_DIR,
            device.image_tag,
            device.platform,
            device.build_args,
        )
        if device.needs_systemd:
            _probe_systemd(device, tag)
        return tag

    return _image


def _installer_fixture(device_name: str):
    @pytest.fixture(scope="session")
    def _installer():
        return build_bundle(DEVICES[device_name], REPO_ROOT)

    return _installer


def _session_fixture(device_name: str, image_fixture: str, installer_fixture: str):
    @pytest.fixture
    def _session(request):
        device = DEVICES[device_name]
        image = request.getfixturevalue(image_fixture)
        installer = request.getfixturevalue(installer_fixture)
        container = Container(
            image=image,
            platform=device.platform,
            mounts={str(REPO_ROOT): "/repo:ro"},
            cap_add=("NET_ADMIN",),
            privileged=device.needs_systemd,
            tmpfs=("/run", "/run/lock") if device.needs_systemd else (),
            command=("/sbin/init",) if device.needs_systemd else ("sleep", "infinity"),
        )
        container.start()
        try:
            if device.needs_systemd:
                container.wait_for_systemd()
            control = FakeRaControl(container)
            control.install()
            emulator = Emulator(container, port=device.proxy_port)
            emulator.install()
            _start_fake_ra(container, control)
            container.put(installer, "/tmp/RAOfflineProxy-Install.sh")
            container.exec("chmod +x /tmp/RAOfflineProxy-Install.sh", check=True)
            if device.run_as:
                # docker cp lands the file as root, and /tmp is sticky, so the
                # device account could not delete the installer the way it does
                # on hardware (where it sits in its own ROMs partition).
                container.exec(
                    "chown %s /tmp/RAOfflineProxy-Install.sh" % device.run_as,
                    check=True,
                )
            yield DeviceSession(container, device, control, emulator)
        finally:
            container.stop()

    return _session


knulli_image = _image_fixture("knulli")
knulli_installer = _installer_fixture("knulli")
knulli = _session_fixture("knulli", "knulli_image", "knulli_installer")

rocknix_image = _image_fixture("rocknix")
rocknix_installer = _installer_fixture("rocknix")
rocknix = _session_fixture("rocknix", "rocknix_image", "rocknix_installer")

muos_image = _image_fixture("muos")
muos_installer = _installer_fixture("muos")
muos = _session_fixture("muos", "muos_image", "muos_installer")

onion_image = _image_fixture("onion")
onion_installer = _installer_fixture("onion")
onion = _session_fixture("onion", "onion_image", "onion_installer")

spruce_image = _image_fixture("spruce")
spruce_installer = _installer_fixture("spruce")
spruce = _session_fixture("spruce", "spruce_image", "spruce_installer")

allium_image = _image_fixture("allium")
allium_installer = _installer_fixture("allium")
allium = _session_fixture("allium", "allium_image", "allium_installer")

darkos_image = _image_fixture("darkos")
darkos_installer = _installer_fixture("darkos")
darkos = _session_fixture("darkos", "darkos_image", "darkos_installer")
