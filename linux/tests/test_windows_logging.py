import logging
import logging.handlers
import sys

import pytest

pytestmark = pytest.mark.skipif(sys.platform != "win32", reason="Windows file sharing")


def record(text: str) -> logging.LogRecord:
    return logging.LogRecord("raofflineproxy", logging.INFO, __file__, 1, text, None, None)


def raise_errors(handler: logging.Handler) -> logging.Handler:
    """logging normally swallows a failed write (as it did in service.log)."""
    def handle_error(_record):
        raise sys.exc_info()[1]

    handler.handleError = handle_error
    return handler


def test_the_log_rolls_over_while_another_process_has_it_open(tmp_path):
    from linux.raofflineproxy.windows.process import rotating_log_handler

    path = tmp_path / "service.log"
    # The menu (or a command) logging to the same file keeps its own handle.
    other = raise_errors(rotating_log_handler(str(path), 10_000, 1, "utf-8"))
    other.emit(record("menu started"))
    service = raise_errors(rotating_log_handler(str(path), 200, 1, "utf-8"))
    try:
        for index in range(20):
            service.emit(record(f"request {index} " + "x" * 20))
        assert (tmp_path / "service.log.1").exists()
        assert path.stat().st_size < 200
    finally:
        other.close()
        service.close()


def test_the_plain_handler_could_not(tmp_path):
    """Why the shared handler exists: the standard one fails exactly here."""
    path = tmp_path / "service.log"
    other = logging.handlers.RotatingFileHandler(str(path), maxBytes=10_000, backupCount=1, encoding="utf-8")
    other.emit(record("menu started"))
    service = raise_errors(logging.handlers.RotatingFileHandler(str(path), maxBytes=200, backupCount=1, encoding="utf-8"))
    try:
        with pytest.raises(PermissionError):
            for index in range(20):
                service.emit(record(f"request {index} " + "x" * 20))
    finally:
        other.close()
        service.close()


def test_the_log_rolls_over_while_the_service_writes_its_output_to_it(tmp_path):
    """The service is started with its stdout/stderr on service.log and keeps
    that handle for its whole life; it must not block the rename either."""
    import subprocess

    from linux.raofflineproxy.windows.process import open_log_for_append

    path = tmp_path / "service.log"
    with open_log_for_append(str(path), "utf-8", None) as handle:
        child = subprocess.Popen(
            [sys.executable, "-c", "import time; print('started', flush=True); time.sleep(30)"],
            stdout=handle, stderr=handle,
        )
    try:
        for _ in range(50):
            if path.exists() and path.stat().st_size:
                break
            __import__("time").sleep(0.1)
        path.rename(tmp_path / "service.log.1")  # what the rollover does
        assert (tmp_path / "service.log.1").read_text().startswith("started")
    finally:
        child.kill()
        child.wait()
