from __future__ import annotations

import re
import time
import xml.etree.ElementTree as ET

from app.e2e.harness.adb import Adb

DUMP_PATH = "/sdcard/raop_window_dump.xml"
BOUNDS = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")


class UiNotFound(AssertionError):
    pass


class Ui:
    """Finds views by resource id in a uiautomator dump and taps them.

    Deliberately thin: the app is driven the way a user drives it, so the
    start/stop path under test is the real MainViewModel one.
    """

    def __init__(self, adb: Adb, package: str) -> None:
        self.adb = adb
        self.package = package

    def _qualified(self, resource_id: str) -> str:
        return resource_id if ":" in resource_id else "%s:id/%s" % (self.package, resource_id)

    def dump(self) -> ET.Element | None:
        # uiautomator refuses to dump while the window is not idle; the caller retries.
        result = self.adb.shell("uiautomator dump %s" % DUMP_PATH, check=False, timeout=60)
        if result.returncode != 0 or "dumped to" not in result.stdout:
            return None
        xml = self.adb.shell("cat %s" % DUMP_PATH, check=False).stdout
        try:
            return ET.fromstring(xml)
        except ET.ParseError:
            return None

    def find(self, resource_id: str, text: str | None = None) -> dict | None:
        root = self.dump()
        if root is None:
            return None
        qualified = self._qualified(resource_id)
        for node in root.iter("node"):
            if node.get("resource-id") != qualified:
                continue
            if text is not None and node.get("text") != text:
                continue
            return dict(node.attrib)
        return None

    def wait_for(
        self,
        resource_id: str,
        text: str | None = None,
        enabled: bool | None = None,
        timeout: float = 60.0,
    ) -> dict:
        deadline = time.time() + timeout
        last = None
        while time.time() < deadline:
            last = self.find(resource_id, text)
            if last is not None and (enabled is None or (last.get("enabled") == "true") is enabled):
                return last
            time.sleep(1.0)
        raise UiNotFound(
            "view %s (text=%r enabled=%r) not found; last seen: %r"
            % (resource_id, text, enabled, last)
        )

    def tap(self, node: dict) -> None:
        match = BOUNDS.match(node.get("bounds", ""))
        if match is None:
            raise UiNotFound("view has no bounds: %r" % node)
        left, top, right, bottom = (int(value) for value in match.groups())
        self.adb.shell("input tap %d %d" % ((left + right) // 2, (top + bottom) // 2))
