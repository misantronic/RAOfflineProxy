from __future__ import annotations

import io
import json
import logging
import urllib.error
import urllib.request
import zipfile
from pathlib import Path

from . import config
from .network import configured_ssl_context
from .utils import redact_query_tokens

LOGGER = logging.getLogger(__name__)

REQUEST_UPLOAD_URL = "https://ud63psmdb5.execute-api.eu-central-1.amazonaws.com/logs/request-upload"
REQUEST_TIMEOUT_SECONDS = 15


def _log_file_paths() -> list[Path]:
    # RotatingFileHandler renames the active file to "<name>.1" on rollover, so the
    # backup can hold the most relevant history if a rollover just happened. Oldest first.
    backup = config.LOG_FILE.with_name(config.LOG_FILE.name + ".1")
    # menu-sdl.log is a separate log written by the pygame menu process itself
    # (key logger output, controller calibration, crashes) — not covered by service.log.
    menu_sdl_log = config.CONFIG_DIR / "menu-sdl.log"
    return [backup, config.LOG_FILE, menu_sdl_log, config.UPDATE_STATUS_FILE, config.STATUS_FILE]


def _read_redacted_log_files() -> dict[str, str]:
    # redact_query_tokens does an in-place substring replacement, safe on arbitrary
    # free-text log lines. redact_form_tokens fully reparses/re-encodes its input as
    # a query string, which would mangle a formatted log line (timestamp, message
    # text, etc.) around the secret rather than just blanking it out.
    files: dict[str, str] = {}
    for path in _log_file_paths():
        try:
            content = path.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        redacted_lines = (redact_query_tokens(line) for line in content.splitlines())
        files[path.name] = "\n".join(redacted_lines)
    return files


def _zip_logs(files: dict[str, str]) -> bytes:
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as archive:
        for name, content in files.items():
            archive.writestr(name, content.encode("utf-8"))
    return buffer.getvalue()


def _request_upload_target() -> tuple[str, str]:
    request = urllib.request.Request(
        REQUEST_UPLOAD_URL,
        data=b"",
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(
            request, timeout=REQUEST_TIMEOUT_SECONDS, context=configured_ssl_context()
        ) as response:
            body = response.read().decode("utf-8", errors="replace")
    except urllib.error.HTTPError as error:
        body = error.read().decode("utf-8", errors="replace")
        LOGGER.error(
            "Log upload request-upload failed status=%s body=%s",
            error.code,
            body[:512],
        )
        raise RuntimeError(f"Could not request an upload URL (HTTP {error.code})") from error
    except urllib.error.URLError as error:
        LOGGER.error("Log upload request-upload failed reason=%s", error.reason)
        raise RuntimeError(f"Could not reach upload service: {error.reason}") from error

    try:
        parsed = json.loads(body)
        upload_id = parsed["id"]
        upload_url = parsed["uploadUrl"]
    except (json.JSONDecodeError, KeyError, TypeError) as error:
        LOGGER.error("Log upload request-upload returned malformed response body=%s", body[:512])
        raise RuntimeError("Malformed request-upload response") from error

    return upload_id, upload_url


def _put_log_archive(upload_url: str, zip_bytes: bytes) -> None:
    request = urllib.request.Request(
        upload_url,
        data=zip_bytes,
        headers={"Content-Type": "application/zip"},
        method="PUT",
    )
    try:
        with urllib.request.urlopen(
            request, timeout=REQUEST_TIMEOUT_SECONDS, context=configured_ssl_context()
        ):
            return
    except urllib.error.HTTPError as error:
        body = error.read().decode("utf-8", errors="replace")
        LOGGER.error("Log upload PUT failed status=%s body=%s", error.code, body[:512])
        raise RuntimeError(f"Upload failed (HTTP {error.code})") from error
    except urllib.error.URLError as error:
        LOGGER.error("Log upload PUT failed reason=%s", error.reason)
        raise RuntimeError(f"Upload failed: {error.reason}") from error


def upload_logs() -> str:
    files = _read_redacted_log_files()
    zip_bytes = _zip_logs(files)
    try:
        upload_id, upload_url = _request_upload_target()
        _put_log_archive(upload_url, zip_bytes)
    except RuntimeError:
        raise
    except Exception as error:
        LOGGER.exception("Log upload failed unexpectedly")
        raise RuntimeError(f"Log upload failed: {error}") from error
    return upload_id
