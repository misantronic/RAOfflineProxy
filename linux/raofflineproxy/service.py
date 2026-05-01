import logging
import os
import signal
import subprocess
import sys
import time

from .config import LOG_FILE
from .proxy_service import run_proxy_service
from .state import (
    clear_pid,
    clear_service_status,
    load_pid,
    load_service_status,
    save_pid,
    save_service_status,
)

SERVICE_COMMAND_MARKERS = [
    "-m raofflineproxy.main run-service",
    "-m linux.raofflineproxy.main run-service",
]


def process_is_running(pid: int) -> bool:
    try:
        os.kill(pid, 0)
        return True
    except OSError:
        return False


def process_has_exited(pid: int) -> bool:
    try:
        status = subprocess.check_output(
            ["ps", "-p", str(pid), "-o", "stat="], text=True
        ).strip()
    except subprocess.CalledProcessError:
        return True

    if not status:
        return True
    return "Z" in status


def process_matches_service(pid: int) -> bool:
    try:
        command = subprocess.check_output(
            ["ps", "-p", str(pid), "-o", "command="], text=True
        ).strip()
    except subprocess.CalledProcessError:
        return False

    if not command:
        return False
    return any(marker in command for marker in SERVICE_COMMAND_MARKERS)


def discover_service_pid() -> int | None:
    try:
        output = subprocess.check_output(["ps", "-eo", "pid=,command="], text=True)
    except subprocess.CalledProcessError:
        return None

    for raw_line in output.splitlines():
        line = raw_line.strip()
        if not line:
            continue
        pid_text, _, command = line.partition(" ")
        if not pid_text or not command:
            continue
        if any(marker in command for marker in SERVICE_COMMAND_MARKERS):
            try:
                return int(pid_text)
            except ValueError:
                continue
    return None


def tracked_or_discovered_service_pid() -> int | None:
    pid = load_pid()
    if pid is not None and process_is_running(pid) and process_matches_service(pid):
        return pid
    return discover_service_pid()


def save_running_service_state(
    pid: int, config_data: dict, started_at: int | None = None
) -> None:
    save_pid(pid)
    save_service_status(
        {
            "running": True,
            "pid": pid,
            "startedAt": started_at or int(time.time()),
            "proxyHost": config_data.get("proxy_host", "127.0.0.1"),
            "proxyPort": int(config_data.get("proxy_port", 8080)),
        }
    )


def start_service_process(config_data: dict) -> dict:
    pid = tracked_or_discovered_service_pid()
    if pid is not None:
        save_running_service_state(pid, config_data)
        return {"started": False, "already_running": True, "pid": pid}

    LOG_FILE.parent.mkdir(parents=True, exist_ok=True)
    with LOG_FILE.open("a", encoding="utf-8") as log_handle:
        process = subprocess.Popen(
            [sys.executable, "-m", "raofflineproxy.main", "run-service"],
            stdout=log_handle,
            stderr=log_handle,
            stdin=subprocess.DEVNULL,
            close_fds=True,
            start_new_session=True,
        )

    save_running_service_state(process.pid, config_data)
    return {"started": True, "already_running": False, "pid": process.pid}


def stop_service_process(timeout_seconds: int = 10) -> dict:
    pid = tracked_or_discovered_service_pid()
    if pid is None:
        clear_pid()
        clear_service_status()
        return {"stopped": False, "already_stopped": True}

    if not process_is_running(pid):
        clear_pid()
        clear_service_status()
        return {"stopped": False, "already_stopped": True}

    os.kill(pid, signal.SIGTERM)
    deadline = time.time() + timeout_seconds
    while time.time() < deadline:
        if process_has_exited(pid):
            clear_pid()
            clear_service_status()
            return {"stopped": True, "already_stopped": False, "pid": pid}
        time.sleep(0.25)

    os.kill(pid, signal.SIGKILL)
    clear_pid()
    clear_service_status()
    return {"stopped": True, "already_stopped": False, "pid": pid, "forced": True}


def service_status() -> dict:
    pid = tracked_or_discovered_service_pid()
    status = load_service_status() or {}
    running = pid is not None and process_is_running(pid)

    if not running:
        clear_pid()
        status["running"] = False
        if pid is not None or "pid" in status:
            status["pid"] = pid
        save_service_status(status)
    else:
        if "startedAt" not in status:
            status["startedAt"] = int(time.time())
        status["running"] = True
        status["pid"] = pid
        status["proxyHost"] = status.get("proxyHost", "127.0.0.1")
        status["proxyPort"] = int(status.get("proxyPort", 8080))
        save_pid(pid)
        save_service_status(status)

    return status


def run_service_foreground(config_data: dict) -> None:
    stop_requested = False

    def handle_signal(_signum: int, _frame: object) -> None:
        nonlocal stop_requested
        stop_requested = True

    signal.signal(signal.SIGTERM, handle_signal)
    signal.signal(signal.SIGINT, handle_signal)

    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
    )

    stop_event = __import__("threading").Event()

    def watch_stop() -> None:
        while not stop_requested:
            time.sleep(0.2)
        stop_event.set()

    watcher = __import__("threading").Thread(target=watch_stop, daemon=True)
    watcher.start()

    save_service_status(
        {
            "running": True,
            "pid": os.getpid(),
            "startedAt": int(time.time()),
            "proxyHost": config_data.get("proxy_host", "127.0.0.1"),
            "proxyPort": int(config_data.get("proxy_port", 8080)),
        }
    )
    save_pid(os.getpid())

    try:
        run_proxy_service(config_data, stop_event)
    finally:
        clear_pid()
        save_service_status(
            {
                "running": False,
                "pid": os.getpid(),
                "stoppedAt": int(time.time()),
            }
        )
