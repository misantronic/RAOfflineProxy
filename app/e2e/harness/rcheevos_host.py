from __future__ import annotations

import json
import urllib.error
import urllib.request

from linux.tests.e2e.harness.rcheevos import Emulator

ANDROID_UA = "RetroArch/1.21.0 (Android 14)"


class HostEmulator(Emulator):
    """Replays the rcheevos request sequence from the host through adb forward."""

    def __init__(self, port: int, user_agent: str = ANDROID_UA) -> None:
        super().__init__(container=None, port=port, user_agent=user_agent)

    def install(self) -> None:
        pass

    def request(self, body: str, user_agent: str | None = None) -> tuple:
        request = urllib.request.Request(
            "http://127.0.0.1:%d/dorequest.php" % self.port,
            data=body.encode("utf-8"),
            headers={
                "User-Agent": user_agent or self.user_agent,
                "Content-Type": "application/x-www-form-urlencoded",
            },
            method="POST",
        )
        try:
            with urllib.request.urlopen(request, timeout=20) as response:
                status, payload = response.status, response.read().decode("utf-8")
        except urllib.error.HTTPError as error:
            status, payload = error.code, error.read().decode("utf-8")
        return status, json.loads(payload)
