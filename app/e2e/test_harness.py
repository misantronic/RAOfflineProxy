from __future__ import annotations

import json
import subprocess
import urllib.request

import pytest

from app.e2e.harness.device import AndroidDevice
from app.e2e.harness.fake_ra_host import HostFakeRa
from app.e2e.harness.session import PROXY_VALUE, cfg_value, retroarch_cfg
from app.e2e.harness.ui import Ui

NOTIFICATION_DUMP = """\
Current Notification Manager state:
  Notification List:
    NotificationRecord(0x0a1b2c3d: pkg=com.other.app user=UserHandle{0} id=1 tag=null importance=3 key=0|com.other.app|1|null|10150: Notification(channel=x))
      extras={
        android.title=String (Other title)
        android.text=String (Other text)
      }
    NotificationRecord(0x0f0e0d0c: pkg=com.raofflineproxy user=UserHandle{0} id=1 tag=null importance=2 key=0|com.raofflineproxy|1|null|10151: Notification(channel=proxy_service))
      extras={
        android.title=String (Offline)
        android.text=String (Idle · 1 pending award)
      }
"""

UI_DUMP = """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<hierarchy rotation="0">
  <node index="0" text="" resource-id="com.raofflineproxy:id/drawer_layout" enabled="true" bounds="[0,0][1080,2400]">
    <node index="1" text="Start proxy" resource-id="com.raofflineproxy:id/btn_start_proxy" enabled="false" bounds="[100,1200][980,1340]" />
  </node>
</hierarchy>
"""


class FakeAdb:
    def __init__(self, outputs: dict) -> None:
        self.outputs = outputs
        self.commands: list = []

    def shell(self, command: str, check: bool = True, timeout: float = 120, stdin=None):
        self.commands.append(command)
        stdout = next(
            (output for prefix, output in self.outputs.items() if command.startswith(prefix)),
            "",
        )
        return subprocess.CompletedProcess(command, 0, stdout=stdout, stderr="")


def test_cfg_value_reads_quoted_values():
    content = retroarch_cfg(hardcore=True, custom_host=PROXY_VALUE)
    assert cfg_value(content, "cheevos_hardcore_mode_enable") == "true"
    assert cfg_value(content, "cheevos_custom_host") == PROXY_VALUE
    assert cfg_value(content, "missing_key") is None


def test_notification_is_matched_by_package_and_id():
    device = AndroidDevice(FakeAdb({"dumpsys notification": NOTIFICATION_DUMP}))
    assert device.notification("com.raofflineproxy", 1) == ("Offline", "Idle · 1 pending award")
    assert device.notification("com.raofflineproxy", 2) is None


def test_ui_finds_views_and_taps_their_centre():
    adb = FakeAdb({"uiautomator dump": "UI hierchary dumped to: /sdcard/x.xml", "cat ": UI_DUMP})
    ui = Ui(adb, "com.raofflineproxy")

    node = ui.find("btn_start_proxy", text="Start proxy")
    assert node is not None and node["enabled"] == "false"
    assert ui.find("btn_start_proxy", text="Stop proxy") is None

    ui.tap(node)
    assert adb.commands[-1] == "input tap 540 1270"


def test_ui_treats_a_busy_window_as_not_found():
    adb = FakeAdb({"uiautomator dump": "ERROR: could not get idle state."})
    assert Ui(adb, "com.raofflineproxy").find("btn_start_proxy") is None


@pytest.fixture
def fake_ra():
    server = HostFakeRa("127.0.0.1", 0)
    server.start()
    try:
        yield server
    finally:
        server.stop()


def _gameid(server: HostFakeRa, rom_hash: str) -> dict:
    port = server._ra_server.server_address[1]
    request = urllib.request.Request(
        "http://127.0.0.1:%d/dorequest.php" % port,
        data=("r=gameid&m=%s" % rom_hash).encode("utf-8"),
        headers={"User-Agent": "RetroArch/1.21.0 (Android 14)"},
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=10) as response:
        return json.loads(response.read().decode("utf-8"))


def test_host_fake_ra_serves_and_resets(fake_ra):
    assert _gameid(fake_ra, "b43c8b4ec999588c04dad79bb8bcc745")["GameID"] == 1447
    assert fake_ra.actions() == ["gameid"]

    fake_ra.reset()

    assert fake_ra.actions() == []
    assert _gameid(fake_ra, "d" * 32)["GameID"] == 0
