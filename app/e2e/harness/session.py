from __future__ import annotations

import re
import time

from app.e2e.harness.device import AndroidDevice
from app.e2e.harness.fake_ra_host import HostFakeRa
from app.e2e.harness.rcheevos_host import HostEmulator
from app.e2e.harness.ui import Ui

APP_PACKAGE = "com.raofflineproxy"
MAIN_ACTIVITY = APP_PACKAGE + "/.ui.MainActivity"
PROXY_SERVICE = ".service.ProxyService"
PROXY_NOTIFICATION_ID = 1
PREFS_FILE = "shared_prefs/ra_proxy_prefs.xml"

FLYCAST_PACKAGE = "com.flycast.emulator"
FLYCAST_HOST_OVERRIDE_FILE = "files/host_override"

RETROARCH_DIR = "/storage/emulated/0/RetroArch"
RETROARCH_CFG = RETROARCH_DIR + "/retroarch.cfg"
RETROARCH_BACKUP = RETROARCH_DIR + "/retroarch.raofflineproxy.cfg"

PROXY_PORT = 8080
PROXY_VALUE = "127.0.0.1:%d" % PROXY_PORT
PROXY_BASE = "http://" + PROXY_VALUE

USER = "testuser"
TOKEN = "tok-testuser-000000000001"

START_LABEL = "Start proxy"
STOP_LABEL = "Stop proxy"

# Update checks and the smart-cache prompt would reach the internet or pop a
# dialog over the start button; the support button only adds noise to dumps.
SEEDED_PREFS = """<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <boolean name="app_update_check_enabled" value="false" />
    <boolean name="enable_smart_caching" value="false" />
    <boolean name="hide_support_button" value="true" />
</map>
"""


def retroarch_cfg(hardcore: bool, custom_host: str = "") -> str:
    return (
        'cheevos_enable = "true"\n'
        'cheevos_username = "%s"\n'
        'cheevos_token = "%s"\n'
        'cheevos_hardcore_mode_enable = "%s"\n'
        'cheevos_custom_host = "%s"\n'
        'video_driver = "vulkan"\n'
    ) % (USER, TOKEN, "true" if hardcore else "false", custom_host)


def cfg_value(content: str, key: str) -> str | None:
    match = re.search(
        r'^\s*%s\s*=\s*"(.*)"\s*$' % re.escape(key), content or "", re.MULTILINE
    )
    return match.group(1) if match else None


def wait_until(predicate, timeout: float, interval: float = 1.0, message: str = "condition"):
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        last = predicate()
        if last:
            return last
        time.sleep(interval)
    raise AssertionError("timed out after %.0fs waiting for %s (last=%r)" % (timeout, message, last))


class AndroidSession:
    """One test's view of the app: fresh data, seeded config, driven through the UI."""

    def __init__(self, device: AndroidDevice, ra: HostFakeRa, emulator: HostEmulator) -> None:
        self.device = device
        self.ra = ra
        self.emulator = emulator
        self.ui = Ui(device.adb, APP_PACKAGE)

    def reset(self, hardcore: bool = True) -> None:
        self.device.set_airplane_mode(False)
        self.device.clear_data(APP_PACKAGE)
        self.device.remove_app_file(FLYCAST_PACKAGE, FLYCAST_HOST_OVERRIDE_FILE)
        self.device.allow_all_files_access(APP_PACKAGE)
        if self.device.sdk_int() >= 33:
            self.device.grant(APP_PACKAGE, "android.permission.POST_NOTIFICATIONS")
        self.device.write_app_file(APP_PACKAGE, PREFS_FILE, SEEDED_PREFS)
        self.device.remove(RETROARCH_BACKUP)
        self.seed_cfg(hardcore=hardcore)

    def seed_cfg(self, hardcore: bool) -> None:
        self.device.write_file(RETROARCH_CFG, retroarch_cfg(hardcore))

    def launch(self) -> None:
        self.device.launch(MAIN_ACTIVITY)
        self.ui.wait_for("btn_start_proxy", text=START_LABEL, enabled=True, timeout=90)

    def teardown(self) -> None:
        self.device.set_airplane_mode(False)
        self.device.force_stop(APP_PACKAGE)

    def cfg(self) -> str:
        return self.device.read_file(RETROARCH_CFG) or ""

    def cfg_value(self, key: str) -> str | None:
        return cfg_value(self.cfg(), key)

    def backup(self) -> str | None:
        return self.device.read_file(RETROARCH_BACKUP)

    def flycast_host_override(self) -> str | None:
        content = self.device.read_app_file(FLYCAST_PACKAGE, FLYCAST_HOST_OVERRIDE_FILE)
        return None if content is None else content.strip()

    def proxy_service_running(self) -> bool:
        return self.device.service_running(APP_PACKAGE, PROXY_SERVICE)

    def notification(self) -> tuple | None:
        return self.device.notification(APP_PACKAGE, PROXY_NOTIFICATION_ID)

    def _wait_for_notification(self, matches, timeout: float, message: str) -> tuple:
        def matching_notification():
            notification = self.notification()
            return notification if notification and matches(notification) else None

        return wait_until(matching_notification, timeout, message=message)

    def wait_for_notification_title(self, title: str, timeout: float = 120.0) -> tuple:
        return self._wait_for_notification(
            lambda notification: notification[0] == title,
            timeout,
            "notification title %r" % title,
        )

    def wait_for_notification_text(self, fragment: str, timeout: float = 120.0) -> tuple:
        return self._wait_for_notification(
            lambda notification: fragment in (notification[1] or ""),
            timeout,
            "notification text containing %r" % fragment,
        )

    def tap_proxy_button(self, label: str) -> None:
        node = self.ui.wait_for("btn_start_proxy", text=label, enabled=True, timeout=60)
        self.ui.tap(node)

    def start_proxy(self) -> None:
        self.tap_proxy_button(START_LABEL)
        wait_until(self.proxy_service_running, 60, message="ProxyService running")
        wait_until(
            lambda: self.cfg_value("cheevos_custom_host") == PROXY_VALUE,
            60,
            message="retroarch.cfg patched",
        )
        self.ui.wait_for("btn_start_proxy", text=STOP_LABEL, enabled=True, timeout=60)

    def stop_proxy(self) -> None:
        self.tap_proxy_button(STOP_LABEL)
        wait_until(lambda: not self.proxy_service_running(), 60, message="ProxyService stopped")
        self.ui.wait_for("btn_start_proxy", text=START_LABEL, timeout=60)

    def wait_until_online(self, timeout: float = 120.0) -> None:
        self.wait_for_notification_title("Online", timeout)

    def go_offline(self, timeout: float = 120.0) -> None:
        self.device.set_airplane_mode(True)
        self.wait_for_notification_title("Offline", timeout)

    def go_online(self, timeout: float = 180.0) -> None:
        self.device.set_airplane_mode(False)
        self.wait_until_online(timeout)
