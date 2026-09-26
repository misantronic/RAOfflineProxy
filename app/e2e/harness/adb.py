from __future__ import annotations

import os
import shutil
import subprocess


class AdbError(RuntimeError):
    pass


def adb_binary() -> str | None:
    for variable in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        sdk = os.environ.get(variable)
        if sdk:
            candidate = os.path.join(sdk, "platform-tools", "adb")
            if os.path.exists(candidate):
                return candidate
    return shutil.which("adb")


class Adb:
    def __init__(self, binary: str, serial: str | None = None) -> None:
        self.binary = binary
        self.serial = serial

    def _base(self) -> list:
        return [self.binary] + (["-s", self.serial] if self.serial else [])

    def run(
        self,
        *args: str,
        check: bool = True,
        timeout: float = 120,
        stdin: str | None = None,
    ) -> subprocess.CompletedProcess:
        stdin_source = {"input": stdin} if stdin is not None else {"stdin": subprocess.DEVNULL}
        result = subprocess.run(
            self._base() + list(args),
            capture_output=True,
            text=True,
            timeout=timeout,
            **stdin_source,
        )
        if check and result.returncode != 0:
            raise AdbError(
                "adb %s failed (%d):\n%s%s"
                % (" ".join(args), result.returncode, result.stdout, result.stderr)
            )
        return result

    def shell(
        self,
        command: str,
        check: bool = True,
        timeout: float = 120,
        stdin: str | None = None,
    ) -> subprocess.CompletedProcess:
        return self.run("shell", command, check=check, timeout=timeout, stdin=stdin)

    def devices(self) -> list:
        result = self.run("devices", check=False, timeout=30)
        rows = [line.split("\t") for line in result.stdout.splitlines()[1:]]
        return [row[0] for row in rows if len(row) == 2 and row[1] == "device"]
