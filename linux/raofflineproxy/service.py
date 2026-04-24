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


def start_service_process(config_data: dict) -> dict:
    pid = load_pid()
    if pid is not None and process_is_running(pid):
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

    save_pid(process.pid)
    save_service_status(
        {
            "running": True,
            "pid": process.pid,
            "startedAt": int(time.time()),
            "proxyHost": config_data.get("proxy_host", "127.0.0.1"),
            "proxyPort": int(config_data.get("proxy_port", 8080)),
        }
    )
    return {"started": True, "already_running": False, "pid": process.pid}


def stop_service_process(timeout_seconds: int = 10) -> dict:
    pid = load_pid()
    if pid is None:
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
    pid = load_pid()
    status = load_service_status() or {}
    running = pid is not None and process_is_running(pid)

    if not running:
        clear_pid()
        status["running"] = False
        if pid is not None or "pid" in status:
            status["pid"] = pid
        save_service_status(status)
    else:
        status["running"] = True
        status["pid"] = pid

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
