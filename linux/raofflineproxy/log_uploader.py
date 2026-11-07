from __future__ import annotations

import io
import json
import logging
import re
import urllib.error
import urllib.request
import zipfile
from pathlib import Path

from . import config
from .emulators import ppsspp_cfg, retroarch_cfg
from . import platforms
from .network import configured_ssl_context
from .utils import redact_query_tokens

LOGGER = logging.getLogger(__name__)

REQUEST_UPLOAD_URL = "https://ud63psmdb5.execute-api.eu-central-1.amazonaws.com/logs/request-upload"
REQUEST_TIMEOUT_SECONDS = 15
QUARANTINED_STORAGE_GLOB = "*.json.corrupt-*"
# Matches a pending_awards/api_cache JSON object whose cacheKey identifies it as
# holding RA credentials (login2:: responses carry a session Token; the
# auth::invalid_token entry's responseBody *is* a raw token), and blanks only
# that object's responseBody value. DOTALL + non-greedy so it stops at the
# first responseBody after the matched cacheKey, i.e. the same object's.
_SENSITIVE_CACHE_ENTRY = re.compile(
    r'("cacheKey":\s*"(?:login2::|auth::invalid_token)[^"]*".*?"responseBody":\s*")'
    r'((?:\\.|[^"\\])*)(")',
    re.DOTALL,
)


def _log_file_paths() -> list[Path]:
    # RotatingFileHandler renames the active file to "<name>.1" on rollover, so the
    # backup can hold the most relevant history if a rollover just happened. Oldest first.
    backup = config.LOG_FILE.with_name(config.LOG_FILE.name + ".1")
    # menu-sdl.log is a separate log written by the pygame menu process itself
    # (key logger output, controller calibration, crashes) — not covered by service.log.
    menu_sdl_log = config.CONFIG_DIR / "menu-sdl.log"
    # launch.log (muOS only) timestamps every launcher invocation, including the
    # boot hook — the only way to see how late the service starts at boot.
    launch_log = config.CONFIG_DIR / "launch.log"
    # menu-stdout.log (ROCKNIX only) holds the raw stdout/stderr of each launcher
    # attempt. A native SDL crash kills the process before any Python handler runs,
    # so this is the only record of which driver/preload combination died and how.
    menu_stdout_log = config.CONFIG_DIR / "menu-stdout.log"
    return [
        backup,
        config.LOG_FILE,
        menu_sdl_log,
        launch_log,
        menu_stdout_log,
        config.UPDATE_STATUS_FILE,
        config.STATUS_FILE,
    ] + platforms.current().extra_log_files()


def _redact_storage_json(text: str) -> str:
    text = _SENSITIVE_CACHE_ENTRY.sub(r"\1<redacted>\3", text)
    return "\n".join(redact_query_tokens(line) for line in text.splitlines())


def _quarantined_storage_files() -> dict[str, str]:
    files: dict[str, str] = {}
    for path in sorted(config.CONFIG_DIR.glob(QUARANTINED_STORAGE_GLOB)):
        try:
            content = path.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        files[path.name] = _redact_storage_json(content)
    return files


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
    files.update(_quarantined_storage_files())
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
        data=json.dumps(device_metadata()).encode("utf-8"),
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


def device_metadata() -> dict[str, str | list[str]]:
    # Submitted alongside the log so the support form can skip asking for this again once the
    # user provides a Log ID. Best-effort: precise emulator/core isn't reliably detectable
    # across these distros, so that field is coarser than the Android equivalent (only whether
    # RetroArch was patched, not which core).
    platform = platforms.current()
    version_value = platform.os_version()

    metadata: dict[str, str | list[str]] = {
        "system": "Linux",
        "os": platform.support_label,
        "device": platform.device_label(),
        "app_version": config.APP_VERSION,
    }
    if version_value:
        metadata["os_version"] = version_value

    config_data = config.load_config()
    emulators: list[str] = []
    if retroarch_cfg.is_retroarch_patched(retroarch_cfg.detect_retroarch_cfg(), config_data):
        emulators.append("RetroArch")
    if ppsspp_cfg.is_ppsspp_patched(config_data):
        emulators.append("PPSSPP")
    if emulators:
        metadata["emulator"] = emulators

    metadata.update(platform.metadata_overrides(config_data))

    return metadata


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


def upload_logs(extra_files: dict[str, str] | None = None) -> str:
    files = _read_redacted_log_files()
    if extra_files:
        files.update(extra_files)
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


def report_storage_corruption(incident: dict) -> str:
    lost_pending_awards = incident.get("lost_pending_awards")
    note = (
        "A corrupted local data file was detected and automatically reset.\n"
        f"Detected at (ms since epoch): {incident.get('detected_at')}\n"
        f"Quarantined file: {incident.get('quarantined_path')}\n"
        f"Corrupted file size (bytes): {incident.get('size_bytes')}\n"
        f"Parse error: {incident.get('reason')}\n"
        f"Salvaged pending_awards count: {lost_pending_awards if lost_pending_awards is not None else 'unknown (unrecoverable)'}\n"
    )
    return upload_logs({"storage_corruption.txt": note})
