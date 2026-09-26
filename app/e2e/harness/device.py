from __future__ import annotations

import base64
import re
import shlex
import time

from app.e2e.harness.adb import Adb

NOTIFICATION_RECORD = re.compile(r"NotificationRecord\(")
NOTIFICATION_TITLE = re.compile(r"android\.title=\S+ \((.*)\)")
NOTIFICATION_TEXT = re.compile(r"android\.text=\S+ \((.*)\)")


def _b64(content: str) -> str:
    return base64.b64encode(content.encode("utf-8")).decode("ascii")


class AndroidDevice:
    """Device-level operations over adb; knows nothing about the app under test."""

    def __init__(self, adb: Adb) -> None:
        self.adb = adb

    def wait_for_boot(self, timeout: float = 600.0) -> None:
        self.adb.run("wait-for-device", timeout=timeout)
        deadline = time.time() + timeout
        while time.time() < deadline:
            result = self.adb.shell("getprop sys.boot_completed", check=False)
            if result.stdout.strip() == "1":
                return
            time.sleep(2.0)
        raise TimeoutError("device did not finish booting")

    def sdk_int(self) -> int:
        return int(self.adb.shell("getprop ro.build.version.sdk").stdout.strip())

    def disable_animations(self) -> None:
        for key in (
            "window_animation_scale",
            "transition_animation_scale",
            "animator_duration_scale",
        ):
            self.adb.shell("settings put global %s 0" % key)

    def install(self, apk: str) -> None:
        self.adb.run("install", "-r", "-g", apk, timeout=300)

    def is_installed(self, package: str) -> bool:
        result = self.adb.shell("pm path %s" % package, check=False)
        return result.stdout.strip().startswith("package:")

    def clear_data(self, package: str) -> None:
        self.adb.shell("pm clear %s" % package)

    def force_stop(self, package: str) -> None:
        self.adb.shell("am force-stop %s" % package)

    def grant(self, package: str, permission: str) -> None:
        self.adb.shell("pm grant %s %s" % (package, permission))

    def allow_all_files_access(self, package: str) -> None:
        self.adb.shell("appops set %s MANAGE_EXTERNAL_STORAGE allow" % package)

    def launch(self, component: str) -> None:
        self.adb.shell("am start -W -n %s" % component, timeout=60)

    def forward(self, local_port: int, device_port: int) -> None:
        self.adb.run("forward", "tcp:%d" % local_port, "tcp:%d" % device_port)

    def remove_forward(self, local_port: int) -> None:
        self.adb.run("forward", "--remove", "tcp:%d" % local_port, check=False)

    def write_file(self, path: str, content: str) -> None:
        parent = path.rsplit("/", 1)[0]
        self.adb.shell(
            "mkdir -p %s && base64 -d > %s"
            % (shlex.quote(parent), shlex.quote(path)),
            stdin=_b64(content),
        )

    def read_file(self, path: str) -> str | None:
        result = self.adb.shell("cat %s" % shlex.quote(path), check=False)
        return result.stdout if result.returncode == 0 else None

    def exists(self, path: str) -> bool:
        return self.adb.shell("test -e %s" % shlex.quote(path), check=False).returncode == 0

    def remove(self, path: str) -> None:
        self.adb.shell("rm -f %s" % shlex.quote(path), check=False)

    def write_app_file(self, package: str, relative_path: str, content: str) -> None:
        parent = relative_path.rsplit("/", 1)[0] if "/" in relative_path else "."
        self.adb.shell(
            "run-as %s sh -c %s"
            % (
                package,
                shlex.quote(
                    "mkdir -p %s && base64 -d > %s"
                    % (shlex.quote(parent), shlex.quote(relative_path))
                ),
            ),
            stdin=_b64(content),
        )

    def remove_app_file(self, package: str, relative_path: str) -> None:
        self.adb.shell("run-as %s rm -f %s" % (package, shlex.quote(relative_path)), check=False)

    def read_app_file(self, package: str, relative_path: str) -> str | None:
        result = self.adb.shell(
            "run-as %s cat %s" % (package, shlex.quote(relative_path)), check=False
        )
        return result.stdout if result.returncode == 0 else None

    def set_airplane_mode(self, enabled: bool) -> None:
        self.adb.shell(
            "cmd connectivity airplane-mode %s" % ("enable" if enabled else "disable")
        )

    def service_running(self, package: str, service_class: str) -> bool:
        result = self.adb.shell("dumpsys activity services %s" % package, check=False)
        return service_class in result.stdout

    def notification(self, package: str, notification_id: int) -> tuple | None:
        """(title, text) of one posted notification, or None when absent."""
        dump = self.adb.shell("dumpsys notification --noredact", check=False).stdout
        for record in NOTIFICATION_RECORD.split(dump)[1:]:
            header = record.split("\n", 1)[0]
            if "pkg=%s " % package not in header or " id=%d " % notification_id not in header:
                continue
            title = NOTIFICATION_TITLE.search(record)
            text = NOTIFICATION_TEXT.search(record)
            return (
                title.group(1) if title else None,
                text.group(1) if text else None,
            )
        return None
