from __future__ import annotations

import json
import re
import sqlite3
import tempfile
import time
from pathlib import Path

from app.e2e.harness.device import AndroidDevice
from app.e2e.harness.fake_ra_host import HostFakeRa
from app.e2e.harness.rcheevos_host import HostEmulator
from app.e2e.harness.ui import Ui

APP_PACKAGE = "com.raofflineproxy"
MAIN_ACTIVITY = APP_PACKAGE + "/.ui.MainActivity"
PROXY_SERVICE = ".service.ProxyService"
PROXY_NOTIFICATION_ID = 1
PREFS_FILE = "shared_prefs/ra_proxy_prefs.xml"

CONFIG_URI = "content://%s.config" % APP_PACKAGE
CLIENT_RECEIVER = "com.raofflineproxy.e2e.client/.ControlReceiver"
CONTROL_OUTPUT = re.compile(r'-> result=(\S+) status=(\{.*\})"')

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

# The toolbar toggle rather than the home button: Android 15+ swaps the home
# button for "Manual Emulator Setup" whenever a config-file emulator is installed.
PROXY_TOGGLE = "action_proxy_root"
PROXY_TOGGLE_LABEL = "tv_proxy_label"

# Update checks, the smart-cache prompt and the usage-stats consent would reach
# the internet or pop a dialog over the proxy toggle; the support button only
# adds noise to dumps. Declined consent also keeps every other scenario from
# reporting usage stats to the fake server.
SEEDED_PREFS = """<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <boolean name="app_update_check_enabled" value="false" />
    <boolean name="enable_smart_caching" value="false" />
    <boolean name="hide_support_button" value="true" />
    <boolean name="usage_stats_consent" value="false" />
</map>
"""

# The same seed with usage stats agreed to (consent version 1), for the scenario
# that checks what a report contains.
CONSENTED_PREFS = SEEDED_PREFS.replace(
    '<boolean name="usage_stats_consent" value="false" />',
    '<boolean name="usage_stats_consent" value="true" />\n'
    '    <int name="usage_stats_consent_version" value="1" />',
)

def retroarch_cfg(hardcore: bool, custom_host: str = "") -> str:
    return (
        'cheevos_enable = "true"\n'
        'cheevos_username = "%s"\n'
        'cheevos_token = "%s"\n'
        'cheevos_hardcore_mode_enable = "%s"\n'
        'cheevos_custom_host = "%s"\n'
        'video_driver = "vulkan"\n'
    ) % (USER, TOKEN, "true" if hardcore else "false", custom_host)


DATABASE_FILE = "databases/raofflineproxy.db"
LOGIN_KEY_PREFIX = "login2::"


def read_cached_login(database_dir: Path) -> dict | None:
    """The cached login row of an app database copied into database_dir, or None."""
    database = database_dir / "raofflineproxy.db"
    if not database.exists():
        return None
    try:
        connection = sqlite3.connect(str(database))
        try:
            row = connection.execute(
                "SELECT cacheKey, responseBody FROM api_cache WHERE cacheKey LIKE ?",
                (LOGIN_KEY_PREFIX + "%",),
            ).fetchone()
        finally:
            connection.close()
        if row is None:
            return None
        body = json.loads(row[1])
    except (sqlite3.Error, ValueError):
        return None
    return {"user": body.get("User"), "token": body.get("Token")}


def cfg_value(content: str, key: str) -> str | None:
    match = re.search(
        r'^\s*%s\s*=\s*"(.*)"\s*$' % re.escape(key), content or "", re.MULTILINE
    )
    return match.group(1) if match else None


def parse_control_output(output: str) -> tuple:
    """(result, status) from the automation client's broadcast result."""
    match = CONTROL_OUTPUT.search(output)
    if match is None:
        raise AssertionError("unexpected automation client output: %s" % output.strip())
    result = None if match.group(1) == "null" else match.group(1)
    return result, json.loads(match.group(2))


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
        self.device.clear_logcat()
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

    def disable_emulator(self, pref_key: str) -> None:
        """Turns one emulator off in the seeded prefs. Call before launch()."""
        flag = '    <boolean name="%s" value="false" />\n' % pref_key
        self.device.write_app_file(APP_PACKAGE, PREFS_FILE, SEEDED_PREFS.replace("</map>", flag + "</map>"))

    def cached_login(self) -> dict | None:
        with tempfile.TemporaryDirectory() as directory:
            for suffix in ("", "-wal", "-shm"):
                data = self.device.pull_app_file(APP_PACKAGE, DATABASE_FILE + suffix)
                if data is not None:
                    (Path(directory) / ("raofflineproxy.db" + suffix)).write_bytes(data)
            return read_cached_login(Path(directory))

    def wait_for_dialog_button(self, button: int, text: str, timeout: float = 60.0) -> dict:
        return self.ui.wait_for("android:id/button%d" % button, text=text, timeout=timeout)

    def tap_dialog_button(self, button: int, text: str) -> None:
        self.ui.tap(self.wait_for_dialog_button(button, text))

    def tap_start_and_wait_for_login_choice(self) -> None:
        self.tap_proxy_toggle(START_LABEL)
        self.wait_for_dialog_button(2, "Start without")

    def wait_for_proxy_started(self) -> None:
        wait_until(self.proxy_service_running, 60, message="ProxyService running")
        self.wait_for_proxy_toggle(STOP_LABEL)

    def fill_login_form(self, username: str, password: str) -> None:
        # In landscape the soft keyboard goes fullscreen over the form, so the second field
        # cannot be tapped: Tab moves on, and the keyboard is hidden before Save is tapped.
        self.ui.tap(self.ui.wait_for("et_manual_credentials_username", timeout=60))
        self.device.input_text(username)
        self.device.press("KEYCODE_TAB")
        self.device.input_text(password)
        self.device.hide_keyboard()

    def background_and_return(self) -> None:
        self.device.adb.shell("input keyevent KEYCODE_HOME")
        time.sleep(1.0)
        self.device.launch(MAIN_ACTIVITY)

    def launch(self) -> None:
        self.device.launch(MAIN_ACTIVITY)
        self.device.lock_landscape()
        self.ui.wait_for_landscape()
        self.wait_for_proxy_toggle(START_LABEL, timeout=90)

    def seed_usage_consent(self) -> None:
        self.device.force_stop(APP_PACKAGE)
        self.device.write_app_file(APP_PACKAGE, PREFS_FILE, CONSENTED_PREFS)

    def relaunch(self) -> None:
        """A fresh app start, which is one of the usage report's triggers."""
        self.device.force_stop(APP_PACKAGE)
        self.device.launch(MAIN_ACTIVITY)

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

    def wait_for_proxy_toggle(self, label: str, timeout: float = 60.0) -> dict:
        self.ui.wait_for(PROXY_TOGGLE_LABEL, text=label, timeout=timeout)
        return self.ui.wait_for(PROXY_TOGGLE, enabled=True, timeout=timeout)

    def tap_proxy_toggle(self, label: str) -> None:
        self.ui.tap(self.wait_for_proxy_toggle(label))

    def start_proxy(self) -> None:
        self.tap_proxy_toggle(START_LABEL)
        wait_until(self.proxy_service_running, 60, message="ProxyService running")
        wait_until(
            lambda: self.cfg_value("cheevos_custom_host") == PROXY_VALUE,
            60,
            message="retroarch.cfg patched",
        )
        self.wait_for_proxy_toggle(STOP_LABEL)

    def stop_proxy(self) -> None:
        self.tap_proxy_toggle(STOP_LABEL)
        wait_until(lambda: not self.proxy_service_running(), 60, message="ProxyService stopped")
        self.ui.wait_for(PROXY_TOGGLE_LABEL, text=START_LABEL, timeout=60)

    def wait_until_online(self, timeout: float = 120.0) -> None:
        self.wait_for_notification_title("Online", timeout)

    def go_offline(self, timeout: float = 120.0) -> None:
        self.device.set_airplane_mode(True)
        self.wait_for_notification_title("Offline", timeout)

    def go_online(self, timeout: float = 180.0) -> None:
        self.device.set_airplane_mode(False)
        self.wait_until_online(timeout)

    def control(self, method: str) -> tuple:
        """Calls the provider from the automation client, which holds the control permission."""
        output = self.device.adb.shell(
            "am broadcast -n %s --es method %s" % (CLIENT_RECEIVER, method), timeout=120
        ).stdout
        return parse_control_output(output)

    def status(self) -> dict:
        return self.control("status")[1]

    def shell_control(self, method: str) -> str:
        """Calls the provider from the shell, which lacks the control permission."""
        result = self.device.adb.shell(
            "content call --uri %s --method %s" % (CONFIG_URI, method), check=False
        )
        return result.stdout + result.stderr

    def prefs(self) -> str:
        return self.device.read_app_file(APP_PACKAGE, PREFS_FILE) or ""
