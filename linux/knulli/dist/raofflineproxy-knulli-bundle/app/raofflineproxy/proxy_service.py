import json
import logging
import socket
import socketserver
import threading
import time
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler
from urllib.parse import urlsplit

from . import cache_keys
from .award_signing import sign_award
from .batocera_conf import enforce_batocera_conf
from .config import FALLBACK_USER_AGENT, proxy_host, proxy_port, upstream_host
from .flusher import flush_pending_awards
from .network import build_forward_headers, http_post, online_check
from .retroarch_cfg import enforce_patched_cfg
from .rom_cache import cache_session, cache_unlocks, refresh_game_patch
from .storage import Storage, current_millis
from .utils import (
    canonical_reason_phrase,
    extract_action,
    extract_request_param,
    is_hardcore_request,
    parse_form_params,
    proxy_user_agent,
    redact_form_tokens,
    redact_query_tokens,
    sha256_hex,
)

LOGGER = logging.getLogger("raofflineproxy")
MAX_REQUEST_BODY_BYTES = 1_048_576
SOCKET_TIMEOUT_SECONDS = 30

AWARD_ACTIONS = {"awardachievement", "submitlbentry"}
FAKE_OFFLINE_SUCCESS_ACTIONS = {"ping"}
CACHEABLE_ACTIONS = {
    "patch",
    "gameid",
    "achievements",
    "hashlibrary",
    "login2",
    "unlocks",
}


def cache_key_for_request(path: str, body: str) -> str:
    action = extract_action(path, body) or "unknown"
    game_id = (
        extract_request_param(path, body, "g")
        or extract_request_param(path, body, "i")
        or ""
    )
    hash_value = extract_request_param(path, body, "m") or ""
    user = extract_request_param(path, body, "u") or ""
    hardcore = extract_request_param(path, body, "h") or ""

    if action == "gameid":
        return cache_keys.game_id(hash_value)
    if action == "startsession":
        return cache_keys.start_session(game_id, user)
    if hardcore:
        return f"{action}:{game_id}:{user}:{hardcore}"
    return f"{action}:{game_id}:{user}"


def should_cache_response(response_body: str) -> bool:
    return '"Success":true' in response_body or '"Success": true' in response_body


def response_bytes(code: int, body: str, reason: str | None = None) -> bytes:
    text = body.encode("utf-8")
    reason_phrase = reason or canonical_reason_phrase(code)
    head = (
        f"HTTP/1.1 {code} {reason_phrase}\r\n"
        f"Content-Type: application/json\r\n"
        f"Content-Length: {len(text)}\r\n"
        f"Connection: close\r\n\r\n"
    ).encode("utf-8")
    return head + text


def ok_json(body: str) -> bytes:
    return response_bytes(200, body, "OK")


def error_json(code: int, message: str) -> bytes:
    return response_bytes(
        code,
        json.dumps({"Success": False, "Error": message}, separators=(",", ":")),
        message,
    )


def game_id_cache_miss() -> bytes:
    return ok_json(
        json.dumps(
            {
                "Success": False,
                "Error": "Game not cached. Launch this game while online first.",
                "GameID": 0,
            },
            separators=(",", ":"),
        )
    )


class ThreadingTCPServer(socketserver.ThreadingMixIn, socketserver.TCPServer):
    allow_reuse_address = True
    daemon_threads = True


class ProxyRequestHandler(BaseHTTPRequestHandler):
    server: "ProxyRuntimeServer"

    def setup(self) -> None:
        super().setup()
        self.request.settimeout(SOCKET_TIMEOUT_SECONDS)

    def do_GET(self) -> None:
        self._handle_request()

    def do_POST(self) -> None:
        self._handle_request()

    def log_message(self, format: str, *args) -> None:
        LOGGER.debug(format, *args)

    def _handle_request(self) -> None:
        if self.client_address[0] not in {"127.0.0.1", "::1"}:
            self.wfile.write(error_json(403, "loopback_only"))
            return

        if self.command not in {"GET", "POST"}:
            self.wfile.write(error_json(405, "method not allowed"))
            return

        content_length = int(self.headers.get("Content-Length", "0") or "0")
        if content_length > MAX_REQUEST_BODY_BYTES:
            self.wfile.write(error_json(413, "request body too large"))
            return

        body = self.rfile.read(content_length).decode("utf-8") if content_length else ""
        headers = {key: value for key, value in self.headers.items()}

        LOGGER.info(
            "Request: %s %s body=%s online=%s",
            self.command,
            redact_query_tokens(self.path),
            redact_form_tokens(body),
            self.server.is_online(),
        )

        response = self.server.process_proxy_request(
            self.command, self.path, body, headers
        )
        self.wfile.write(response)


class ProxyRuntimeServer(ThreadingTCPServer):
    def __init__(self, config_data: dict, storage: Storage):
        self.config_data = dict(config_data)
        self.storage = storage
        self.running = True
        self.last_online = False
        self.flush_lock = threading.Lock()
        host = proxy_host(self.config_data)
        port = proxy_port(self.config_data)
        super().__init__((host, port), ProxyRequestHandler)

    def is_online(self) -> bool:
        self.last_online = online_check(self.config_data)
        return self.last_online

    def process_proxy_request(
        self, method: str, path: str, raw_body: str, headers: dict[str, str]
    ) -> bytes:
        user_agent = headers.get("User-Agent") or headers.get("user-agent")
        if user_agent:
            self.storage.upsert_cache(cache_keys.USER_AGENT, user_agent)

        action = extract_action(path, raw_body)
        if action in AWARD_ACTIONS:
            return self.handle_award_request(path, raw_body, headers)

        if is_hardcore_request(path, raw_body):
            if not self.is_online():
                return error_json(503, "upstream unavailable")
            return self.forward_to_upstream(method, path, raw_body, headers)

        if action in FAKE_OFFLINE_SUCCESS_ACTIONS and not self.is_online():
            return ok_json('{"Success":true}')

        if action == "startsession" and not self.is_online():
            return self.handle_start_session(path, raw_body)

        if self.is_online():
            return self.handle_online_request(method, path, raw_body, action, headers)

        return self.handle_offline_request(path, raw_body, action)

    def handle_award_request(
        self, path: str, raw_body: str, headers: dict[str, str]
    ) -> bytes:
        if is_hardcore_request(path, raw_body):
            return error_json(403, "hardcore_not_supported")

        if self.is_online():
            upstream = self.forward_to_upstream_result("POST", path, raw_body, headers)
            if upstream[0] == "success":
                return response_bytes(upstream[1], upstream[3], upstream[2])
            if upstream[0] == "http_error":
                return response_bytes(upstream[1], upstream[3], upstream[2])

        return self.queue_offline_award(path, raw_body, headers)

    def handle_start_session(self, path: str, raw_body: str) -> bytes:
        game_id = extract_request_param(path, raw_body, "g")
        user = extract_request_param(path, raw_body, "u")
        if not game_id or not user:
            return error_json(400, "bad request")

        cache_session(int(game_id), {"user": user, "token": ""}, self.storage)
        cached = self.storage.get_cache(cache_keys.start_session(game_id, user))
        if cached is None:
            return error_json(503, "no cached response")
        return ok_json(cached["responseBody"])

    def queue_offline_award(
        self, path: str, raw_body: str, headers: dict[str, str]
    ) -> bytes:
        queued = self.queue_award(path, raw_body, headers)
        if not queued:
            return error_json(500, "award_queue_failed")

        score = self.fetch_cached_score(path, raw_body)
        return ok_json(
            json.dumps(
                {
                    "Success": True,
                    "Score": score,
                    "SoftcoreScore": 0,
                    "AchievementID": 0,
                    "Error": "queued_offline",
                },
                separators=(",", ":"),
            )
        )

    def handle_online_request(
        self,
        method: str,
        path: str,
        raw_body: str,
        action: str | None,
        headers: dict[str, str],
    ) -> bytes:
        upstream = self.forward_to_upstream_result(method, path, raw_body, headers)
        if upstream[0] != "success":
            return error_json(503, "upstream unavailable")

        status_code = upstream[1]
        reason = upstream[2]
        response_body = upstream[3]
        if should_cache_response(response_body) and action in CACHEABLE_ACTIONS:
            key = cache_key_for_request(path, raw_body)
            self.storage.upsert_cache(key, response_body)
            if action == "patch":
                game_id = extract_request_param(path, raw_body, "g")
                user = extract_request_param(path, raw_body, "u")
                token = extract_request_param(path, raw_body, "t")
                if game_id and user and token:
                    cache_unlocks(
                        int(game_id),
                        {"user": user, "token": token},
                        headers.get("User-Agent") or FALLBACK_USER_AGENT,
                        self.config_data,
                    )
        return response_bytes(status_code, response_body, reason)

    def handle_offline_request(
        self, path: str, raw_body: str, action: str | None
    ) -> bytes:
        if action not in CACHEABLE_ACTIONS:
            return error_json(503, "offline")

        key = cache_key_for_request(path, raw_body)
        cached = self.storage.get_cache(key) or self.storage.get_cache_by_prefix(
            f"{key}:"
        )
        if cached is not None:
            return ok_json(cached["responseBody"])

        if action == "gameid":
            return game_id_cache_miss()
        return error_json(503, "no cached response")

    def forward_to_upstream(
        self, method: str, path: str, raw_body: str, headers: dict[str, str]
    ) -> bytes:
        upstream = self.forward_to_upstream_result(method, path, raw_body, headers)
        if upstream[0] == "success":
            return response_bytes(upstream[1], upstream[3], upstream[2])
        if upstream[0] == "http_error":
            return response_bytes(upstream[1], upstream[3], upstream[2])
        return error_json(503, "upstream unavailable")

    def forward_to_upstream_result(
        self, method: str, path: str, raw_body: str, headers: dict[str, str]
    ) -> tuple[str, int, str, str]:
        url = f"{upstream_host(self.config_data)}{path}"
        request_headers = build_forward_headers(headers)
        try:
            if method == "POST":
                status, reason, response_body = http_post(
                    url, raw_body, request_headers
                )
            else:
                import urllib.request

                request = urllib.request.Request(
                    url, headers=request_headers, method="GET"
                )
                try:
                    with urllib.request.urlopen(request, timeout=15) as response:
                        status = response.status
                        reason = response.reason
                        response_body = response.read().decode("utf-8")
                except Exception as error:
                    if hasattr(error, "read"):
                        status = getattr(error, "code", 500)
                        reason = getattr(
                            error, "reason", canonical_reason_phrase(status)
                        )
                        response_body = error.read().decode("utf-8")
                    else:
                        raise

            if 200 <= status < 300:
                return "success", status, reason, response_body
            return "http_error", status, reason, response_body
        except Exception as error:
            LOGGER.error("Upstream request failed: %s", error)
            return (
                "network_error",
                503,
                "Service Unavailable",
                json.dumps({"Success": False, "Error": str(error)}),
            )

    def queue_award(self, path: str, raw_body: str, headers: dict[str, str]) -> bool:
        achievement_id = int(extract_request_param(path, raw_body, "a") or "0")
        if achievement_id > 0 and self.storage.pending_award_exists(achievement_id):
            return True

        prev_hash = (self.storage.get_latest_pending_award() or {}).get(
            "payloadHash"
        ) or "genesis"
        queued_at = current_millis()
        payload_hash = sha256_hex(f"{achievement_id}|{path}|{raw_body}|{queued_at}")
        sign_input = f"{payload_hash}:{prev_hash}".encode("utf-8")
        signature = sign_award(sign_input)

        award = {
            "achievementId": achievement_id,
            "queryString": path,
            "requestBody": raw_body,
            "userAgent": headers.get("User-Agent")
            or headers.get("user-agent")
            or FALLBACK_USER_AGENT,
            "queuedAt": queued_at,
            "retryCount": 0,
            "lastError": None,
            "payloadHash": payload_hash,
            "prevHash": prev_hash,
            "signature": signature,
            "signedAt": current_millis(),
        }
        self.storage.upsert_pending_award(award)
        return True

    def fetch_cached_score(self, path: str, raw_body: str) -> int:
        user = extract_request_param(path, raw_body, "u")
        if not user:
            return 0
        cached = self.storage.get_cache(cache_keys.login(user))
        if cached is None:
            return 0
        try:
            payload = json.loads(cached["responseBody"])
            return int(payload.get("Score", 0))
        except Exception:
            return 0

    def flush_pending_awards(self) -> dict:
        with self.flush_lock:
            outcome = flush_pending_awards(self.storage, self.config_data)
            return {
                "flushed": outcome.flushed,
                "total": outcome.total,
                "skipped_stale": outcome.skipped_stale,
                "last_error": outcome.last_error,
            }


class ConnectivityMonitor(threading.Thread):
    def __init__(self, server: ProxyRuntimeServer, interval_seconds: int = 15):
        super().__init__(daemon=True)
        self.server = server
        self.interval_seconds = interval_seconds
        self.stop_event = threading.Event()

    def stop(self) -> None:
        self.stop_event.set()

    def run(self) -> None:
        was_online = self.server.is_online()
        while not self.stop_event.wait(self.interval_seconds):
            is_online = self.server.is_online()
            if is_online and not was_online:
                self.server.flush_pending_awards()
            was_online = is_online


class PeriodicRefresh(threading.Thread):
    def __init__(
        self,
        server: ProxyRuntimeServer,
        interval_seconds: int = 3600,
        cache_ttl_seconds: int = 7 * 24 * 3600,
    ):
        super().__init__(daemon=True)
        self.server = server
        self.interval_seconds = interval_seconds
        self.cache_ttl_seconds = cache_ttl_seconds
        self.stop_event = threading.Event()

    def stop(self) -> None:
        self.stop_event.set()

    def run(self) -> None:
        while not self.stop_event.wait(self.interval_seconds):
            if not self.server.is_online():
                continue
            credentials = self.server.storage.load_login_credentials()
            if credentials is None:
                continue
            user_agent = self.server.storage.load_user_agent(FALLBACK_USER_AGENT)
            patch_entries = self.server.storage.get_all_cache_by_prefix(
                cache_keys.PREFIX_PATCH
            )
            game_ids: list[int] = []
            for entry in patch_entries:
                game_id = cache_keys.parse_game_id_from_patch_key(entry["cacheKey"])
                if game_id is not None and game_id not in game_ids:
                    refresh_game_patch(
                        game_id,
                        credentials,
                        user_agent,
                        self.server.storage,
                        self.server.config_data,
                    )
                    cache_unlocks(
                        game_id, credentials, user_agent, self.server.config_data
                    )
                    cache_session(game_id, credentials, self.server.storage)
                    game_ids.append(game_id)
            before = current_millis() - (self.cache_ttl_seconds * 1000)
            self.server.storage.evict_cache_older_than(before)


class ConfigEnforcer(threading.Thread):
    def __init__(self, config_data: dict, interval_seconds: int = 2):
        super().__init__(daemon=True)
        self.config_data = dict(config_data)
        self.interval_seconds = interval_seconds
        self.stop_event = threading.Event()

    def stop(self) -> None:
        self.stop_event.set()

    def run(self) -> None:
        cfg_path = self.config_data.get("retroarch_cfg")
        while not self.stop_event.wait(self.interval_seconds):
            try:
                if cfg_path:
                    changed = enforce_patched_cfg(cfg_path, self.config_data)
                    if changed:
                        LOGGER.info("Re-applied RetroArch proxy patch to %s", cfg_path)
                batocera_changed = enforce_batocera_conf(self.config_data)
                if batocera_changed:
                    LOGGER.info("Re-applied batocera.conf cheevos settings")
            except Exception as error:
                LOGGER.warning("Config enforcer failed: %s", error)


def run_proxy_service(
    config_data: dict, stop_event: threading.Event | None = None
) -> None:
    storage = Storage()
    server = ProxyRuntimeServer(config_data, storage)
    connectivity_monitor = ConnectivityMonitor(server)
    periodic_refresh = PeriodicRefresh(server)
    config_enforcer = ConfigEnforcer(config_data)

    try:
        if server.is_online():
            server.flush_pending_awards()
        connectivity_monitor.start()
        periodic_refresh.start()
        config_enforcer.start()

        if stop_event is None:
            server.serve_forever(poll_interval=0.5)
            return

        serving_thread = threading.Thread(
            target=server.serve_forever,
            kwargs={"poll_interval": 0.5},
            daemon=True,
        )
        serving_thread.start()
        while not stop_event.wait(0.5):
            continue
        server.shutdown()
        serving_thread.join(timeout=5)
    finally:
        config_enforcer.stop()
        connectivity_monitor.stop()
        periodic_refresh.stop()
        server.server_close()
        storage.close()
