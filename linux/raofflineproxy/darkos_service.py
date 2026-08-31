from __future__ import annotations

import os
import subprocess
import time
from datetime import datetime
from pathlib import Path

from .config import DEFAULT_DARKOS_HOME

DEFAULT_DARKOS_AUTOSTART_UNIT = Path("/etc/systemd/system/raofflineproxy.service")
DARKOS_SERVICE_NAME = "raofflineproxy.service"
DARKOS_APP_DIR = DEFAULT_DARKOS_HOME.joinpath('raofflineproxy', 'app')


def _run_privileged(args: list[str]) -> tuple[bool, str]:
    # dArkOS's own ES Tools scripts (e.g. "Enable Remote Services.sh") call
    # `sudo systemctl ...` non-interactively from the same unprivileged Tools
    # context RAOfflineProxy runs in, which only works with passwordless sudo
    # configured for the device user. -n makes sudo fail fast instead of
    # hanging on a password prompt if that assumption turns out to be wrong.
    candidates = [["sudo", "-n", *args]]
    if os.geteuid() == 0:
        candidates.append(list(args))

    for candidate in candidates:
        try:
            result = subprocess.run(candidate, capture_output=True, timeout=10)
        except OSError:
            continue
        if result.returncode == 0:
            return True, result.stdout.decode().strip()

    return False, ""


def _write_privileged(path: Path, content: str) -> bool:
    try:
        path.write_text(content, encoding="utf-8")
        return True
    except OSError:
        pass

    try:
        result = subprocess.run(
            ["sudo", "-n", "tee", str(path)],
            input=content,
            text=True,
            capture_output=True,
            timeout=10,
        )
        return result.returncode == 0
    except OSError:
        return False


def _systemd_timestamp_to_unix(ts: str) -> int:
    # "Mon 2026-08-31 20:02:52 EES" → ["Mon", "2026-08-31", "20:02:52", "EES"]
    parts = ts.split()
    date_and_time = f"{parts[1]} {parts[2]}"  # "2026-08-31 20:02:52"

    dt = datetime.strptime(date_and_time, "%Y-%m-%d %H:%M:%S")
    return int(time.mktime(dt.timetuple()))


def darkos_systemd_unit() -> str:
    return "\n".join(
        [
            "[Unit]",
            "Description=RAOfflineProxy server",
            "After=emulationstation.service network.target",
            "",
            "[Service]",
            "Type=simple",
            f"WorkingDirectory={DARKOS_APP_DIR}",
            f"ExecStart=/usr/bin/python3 -m raofflineproxy.main run-service",
            "Restart=always",
            "RestartSec=1",
            "",
            "[Install]",
            "WantedBy=multi-user.target",
            "",
        ]
    )


def systemd_install_service() -> None:
    if not _write_privileged(
        DEFAULT_DARKOS_AUTOSTART_UNIT, darkos_systemd_unit()
    ):
        return
    _run_privileged(["systemctl", "daemon-reload"])


def systemd_remove_service() -> None:
    _run_privileged(["systemctl", "disable", "--now", DARKOS_SERVICE_NAME])
    if not _run_privileged(["rm", "-f", str(DEFAULT_DARKOS_AUTOSTART_UNIT)])[0]:
        try:
            DEFAULT_DARKOS_AUTOSTART_UNIT.unlink(missing_ok=True)
        except OSError:
            pass
    _run_privileged(["systemctl", "daemon-reload"])


def systemd_service_status() -> bool:
    # Active: active => True
    # Active: inactive => False
    return _run_privileged(["systemctl", "is-active", "--quiet", DARKOS_SERVICE_NAME])[0]


def systemd_service_enabled() -> bool:
    # enabled => True
    # disabled => False
    return _run_privileged(["systemctl", "is-enabled", "--quiet", DARKOS_SERVICE_NAME])[0]


def systemd_service_pid() -> int | None:
    # the following command returns "0" for inactive or non-existing services
    _pid = _run_privileged(["systemctl", "show", "--property", "MainPID", "--value", DARKOS_SERVICE_NAME])[1]
    if _pid != "0":
        return int(_pid)
    return


def systemd_service_start_time() -> int:
    if systemd_service_status():
        ts = _run_privileged(["systemctl", "show", DARKOS_SERVICE_NAME, "--property", "ActiveEnterTimestamp"])[1]
        return _systemd_timestamp_to_unix(ts)
    else:
        return int(time.time())


def systemd_enable_service() -> bool:
    if not DEFAULT_DARKOS_AUTOSTART_UNIT.exists():
        systemd_install_service()
    return _run_privileged(["systemctl", "enable", DARKOS_SERVICE_NAME])[0]


def systemd_disable_service() -> bool:
    return _run_privileged(["systemctl", "disable", DARKOS_SERVICE_NAME])[0]


def systemd_start_service() -> bool:
    return _run_privileged(["systemctl", "start", DARKOS_SERVICE_NAME])[0]


def systemd_stop_service() -> bool:
   return _run_privileged(["systemctl", "stop", DARKOS_SERVICE_NAME])[0]


def darkos_service_start() -> dict:
    _status: dict[str, bool | int | None] = {
        "started": False,
        "already_running": False,
        "_pid": None
    }
    if systemd_service_status():
        _status["already_running"] = True

    if systemd_start_service():
        _status["started"] = True
    _status["pid"] = systemd_service_pid()
    return _status


def darkos_service_stop() -> dict:
    _status: dict[str, bool | int | None] = {
        "stopped": False,
        "already_stopped": False,
        "pid": None,
        "forced": False
    }
    if systemd_service_status():
        _status["pid"] = systemd_service_pid()
    else:
        _status["already_stopped"] = True

    if systemd_stop_service():
        _status["started"] = True
    _status["pid"] = systemd_service_pid()
    return _status


def darkos_service_status() -> dict:
    return {
        "running": systemd_service_status(),
        "pid": systemd_service_pid(),
        "startedAt": systemd_service_start_time(),
        "proxyHost": "127.0.0.1",
        "proxyPort": 8080
    }
