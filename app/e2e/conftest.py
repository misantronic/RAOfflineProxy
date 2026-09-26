from __future__ import annotations

import os
from pathlib import Path

import pytest

from app.e2e.harness.adb import Adb, adb_binary
from app.e2e.harness.apks import build_apks
from app.e2e.harness.device import AndroidDevice
from app.e2e.harness.fake_ra_host import HostFakeRa
from app.e2e.harness.rcheevos_host import HostEmulator
from app.e2e.harness.session import PROXY_PORT, AndroidSession

REPO_ROOT = Path(__file__).resolve().parents[2]

# Must match the e2eRaHost the APK was built with (default http://10.0.2.2:8181).
FAKE_RA_PORT = int(os.environ.get("RAOP_ANDROID_E2E_RA_PORT", "8181"))
HOST_PROXY_PORT = 18080

# All-files access for the direct retroarch.cfg path and `cmd connectivity
# airplane-mode` both need Android 11.
MIN_SDK = 30


def pytest_addoption(parser) -> None:
    parser.addoption(
        "--android-e2e",
        action="store_true",
        default=False,
        help="run the Android emulator E2E scenarios",
    )


def _opted_in(config) -> bool:
    return bool(config.getoption("--android-e2e")) or os.environ.get("RAOP_ANDROID_E2E") == "1"


def pytest_collection_modifyitems(config, items) -> None:
    if _opted_in(config):
        return
    opt_in = pytest.mark.skip(
        reason="Android E2E is opt-in: pass --android-e2e or set RAOP_ANDROID_E2E=1"
    )
    for item in items:
        if "scenarios" in str(item.fspath):
            item.add_marker(opt_in)


@pytest.fixture(scope="session")
def adb() -> Adb:
    binary = adb_binary()
    if binary is None:
        pytest.skip("adb is not available")
    client = Adb(binary, os.environ.get("ANDROID_SERIAL"))
    if not client.devices():
        pytest.skip("no Android device or emulator is attached")
    return client


@pytest.fixture(scope="session")
def fake_ra():
    server = HostFakeRa("127.0.0.1", FAKE_RA_PORT)
    server.start()
    try:
        yield server
    finally:
        server.stop()


@pytest.fixture(scope="session")
def device(adb):
    target = AndroidDevice(adb)
    target.wait_for_boot()
    if target.sdk_int() < MIN_SDK:
        pytest.skip("Android E2E needs API %d+, device is API %d" % (MIN_SDK, target.sdk_int()))
    target.disable_animations()
    for apk in build_apks(REPO_ROOT).all():
        target.install(str(apk))
    target.forward(HOST_PROXY_PORT, PROXY_PORT)
    try:
        yield target
    finally:
        target.set_airplane_mode(False)
        target.remove_forward(HOST_PROXY_PORT)


@pytest.fixture
def android(device, fake_ra):
    fake_ra.reset()
    session = AndroidSession(device, fake_ra, HostEmulator(HOST_PROXY_PORT))
    session.reset()
    session.launch()
    try:
        yield session
    finally:
        session.teardown()
