import json
import logging
from collections.abc import Sequence
from pathlib import Path
import socket
import socketserver
import threading
import time
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler
from urllib.parse import urlsplit

from . import cache_keys
from .auth import resolve_credentials
from .award_signing import sign_award
from .config import FALLBACK_USER_AGENT, proxy_host, proxy_port, upstream_host
from .flusher import flush_pending_awards
from .image_cache import resolve_cached_static_asset
from .network import (
    build_forward_headers,
    decode_response_body,
    http_post,
    is_retroachievements_reachable,
    mark_retroachievements_unreachable,
    probe_retroachievements,
    read_response_bytes,
    response_content_type,
)
from .rom_cache import (
    build_achievement_game_ids,
    build_unlocks_array,
    cache_session,
    cache_unlocks,
    filter_warning_achievement_ids,
    merged_unlock_ids,
    refresh_game_patch,
)
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
FAKE_OFFLINE_SUCCESS_ACTIONS = {"ping", "postactivity"}
ALWAYS_TRY_UPSTREAM_ACTIONS = {"login", "login2"}


def is_static_asset_request(path: str) -> bool:
    clean_path = path.split("?", 1)[0]
    return clean_path.startswith("/Badge/")


def guess_content_type(path: Path) -> str:
    suffix = path.suffix.lower()
    if suffix == ".png":
        return "image/png"
    if suffix in {".jpg", ".jpeg"}:
        return "image/jpeg"
    if suffix == ".gif":
        return "image/gif"
    if suffix == ".webp":
        return "image/webp"
    return "application/octet-stream"


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
    if action in {"login", "login2"}:
        return cache_keys.login(user)
    if action == "achievementsets":
        return cache_keys.achievementsets(hash_value or game_id or "unknown", user)
    if action == "startsession":
        return cache_keys.start_session(game_id, user)
    if action == "patch":
        return cache_keys.patch(game_id, user)
    if action == "unlocks":
        return cache_keys.unlocks(game_id, user)
    if hardcore:
        return f"{action}:{game_id}:{user}:{hardcore}"
    return f"{action}:{game_id}:{user}"


def should_cache_response(response_body: str) -> bool:
    return '"Success":true' in response_body or '"Success": true' in response_body


def should_cache_action(action: str | None, path: str) -> bool:
    return (
        action is not None
        and path.startswith("/dorequest.php")
        and action != "startsession"
    )


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


def raw_response_bytes(
    code: int,
    body: bytes,
    content_type: str | None,
    reason: str | None = None,
) -> bytes:
    reason_phrase = reason or canonical_reason_phrase(code)
    mime_type = content_type or "application/octet-stream"
    head = (
        f"HTTP/1.1 {code} {reason_phrase}\r\n"
        f"Content-Type: {mime_type}\r\n"
        f"Content-Length: {len(body)}\r\n"
        f"Connection: close\r\n\r\n"
    ).encode("utf-8")
    return head + body


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
        self.has_internet = False
        self.flush_lock = threading.Lock()
        host = proxy_host(self.config_data)
        port = proxy_port(self.config_data)
        super().__init__((host, port), ProxyRequestHandler)

    def is_online(self) -> bool:
        self.refresh_reachability(force_probe=False)
        return self.has_internet and is_retroachievements_reachable()

    def refresh_reachability(self, force_probe: bool) -> bool:
        self.has_internet = probe_retroachievements(
            self.config_data,
            user_agent=self.storage.load_user_agent(FALLBACK_USER_AGENT),
            force=force_probe,
        )
        if not self.has_internet:
            mark_retroachievements_unreachable()
            return False
        return is_retroachievements_reachable()

    def process_proxy_request(
        self, method: str, path: str, raw_body: str, headers: dict[str, str]
    ) -> bytes:
        if is_static_asset_request(path):
            cached_asset = resolve_cached_static_asset(path)
            if cached_asset is not None:
                return raw_response_bytes(
                    200,
                    cached_asset.read_bytes(),
                    guess_content_type(cached_asset),
                    "OK",
                )
            return raw_response_bytes(
                204, b"", "application/octet-stream", "No Content"
            )

        user_agent = headers.get("User-Agent") or headers.get("user-agent")
        if user_agent:
            self.storage.upsert_cache(cache_keys.USER_AGENT, user_agent)

        action = extract_action(path, raw_body)
        if action in AWARD_ACTIONS:
            return self.handle_award_request(path, raw_body, headers)

        if action == "unlocks" and not self.is_online():
            game_id = extract_request_param(path, raw_body, "g")
            user = extract_request_param(path, raw_body, "u")
            if game_id and user:
                return ok_json(self.build_offline_unlocks_response(int(game_id), user))

        if action == "startsession" and not self.is_online():
            return self.handle_start_session(path, raw_body)

        if is_hardcore_request(path, raw_body):
            if not self.is_online():
                return error_json(503, "upstream unavailable")
            return self.forward_to_upstream(method, path, raw_body, headers)

        if action in FAKE_OFFLINE_SUCCESS_ACTIONS and not self.is_online():
            return ok_json('{"Success":true}')

        if action in ALWAYS_TRY_UPSTREAM_ACTIONS:
            upstream = self.forward_to_upstream_result(method, path, raw_body, headers)
            if upstream[0] in {"success", "http_error"}:
                status_code = upstream[1]
                reason = upstream[2]
                response_body_bytes = upstream[3]
                content_type = upstream[4]
                response_body = upstream[5]
                if response_body is not None and should_cache_response(response_body):
                    key = cache_key_for_request(path, raw_body)
                    self.storage.upsert_cache(key, response_body)
                return raw_response_bytes(
                    status_code, response_body_bytes, content_type, reason
                )
            cached_login_response = self.handle_offline_login(path, raw_body, action)
            if cached_login_response is not None:
                return cached_login_response

        if self.is_online():
            return self.handle_online_request(method, path, raw_body, action, headers)

        return self.handle_offline_request(path, raw_body, action)

    def handle_offline_login(
        self, path: str, raw_body: str, action: str | None
    ) -> bytes | None:
        if action not in ALWAYS_TRY_UPSTREAM_ACTIONS:
            return None

        user = extract_request_param(path, raw_body, "u")
        if not user:
            return None

        cached = self.storage.get_cache(cache_keys.login(user))
        if cached is None:
            return None

        return ok_json(cached["responseBody"])

    def handle_award_request(
        self, path: str, raw_body: str, headers: dict[str, str]
    ) -> bytes:
        if is_hardcore_request(path, raw_body):
            return error_json(403, "hardcore_not_supported")

        if self.is_online():
            upstream = self.forward_to_upstream_result("POST", path, raw_body, headers)
            if upstream[0] == "success":
                self.schedule_post_award_refresh(path, raw_body, headers)
                return raw_response_bytes(
                    upstream[1], upstream[3], upstream[4], upstream[2]
                )
            if upstream[0] == "http_error":
                return raw_response_bytes(
                    upstream[1], upstream[3], upstream[4], upstream[2]
                )

        return self.queue_offline_award(path, raw_body, headers)

    def schedule_post_award_refresh(
        self, path: str, raw_body: str, headers: dict[str, str]
    ) -> None:
        refresh_thread = threading.Thread(
            target=self.refresh_caches_after_online_award,
            args=(path, raw_body, headers),
            daemon=True,
        )
        refresh_thread.start()

    def refresh_caches_after_online_award(
        self, path: str, raw_body: str, headers: dict[str, str]
    ) -> None:
        game_id = self.resolve_game_id_for_award(path, raw_body)
        user = extract_request_param(path, raw_body, "u")
        token = extract_request_param(path, raw_body, "t")
        if not game_id or not user or not token:
            return

        user_agent = headers.get("User-Agent") or FALLBACK_USER_AGENT
        credentials = {"user": user, "token": token}
        cache_unlocks(
            int(game_id),
            credentials,
            user_agent,
            self.config_data,
            self.storage,
        )
        cache_session(int(game_id), credentials, self.storage)

    def resolve_game_id_for_award(self, path: str, raw_body: str) -> str | None:
        game_id = extract_request_param(path, raw_body, "g")
        if game_id:
            return game_id

        achievement_id = int(extract_request_param(path, raw_body, "a") or "0")
        if achievement_id <= 0:
            return None

        achievement_game_ids = build_achievement_game_ids(
            self.storage.get_all_cache_by_prefix(cache_keys.PREFIX_PATCH)
        )
        resolved_game_id = achievement_game_ids.get(achievement_id)
        if resolved_game_id is None:
            return None
        return str(resolved_game_id)

    def handle_start_session(self, path: str, raw_body: str) -> bytes:
        game_id = extract_request_param(path, raw_body, "g")
        user = extract_request_param(path, raw_body, "u")
        if not game_id or not user:
            return error_json(400, "bad request")

        return ok_json(self.build_offline_start_session_response(int(game_id), user))

    def queue_offline_award(
        self, path: str, raw_body: str, headers: dict[str, str]
    ) -> bytes:
        queued = self.queue_award(path, raw_body, headers)
        if not queued:
            return error_json(500, "award_queue_failed")

        achievement_id = int(extract_request_param(path, raw_body, "a") or "0")
        score = self.fetch_cached_score(path, raw_body)
        return ok_json(
            json.dumps(
                {
                    "Success": True,
                    "Score": score,
                    "SoftcoreScore": 0,
                    "AchievementID": achievement_id,
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
        if upstream[0] == "network_error":
            return self.handle_offline_request(path, raw_body, action)

        if upstream[0] != "success":
            return error_json(503, "upstream unavailable")

        status_code = upstream[1]
        reason = upstream[2]
        response_body_bytes = upstream[3]
        content_type = upstream[4]
        response_body = upstream[5]
        if action is None or not path.startswith("/dorequest.php"):
            return raw_response_bytes(
                status_code, response_body_bytes, content_type, reason
            )

        if response_body is None:
            return error_json(503, "invalid upstream response")

        if action == "startsession" and should_cache_response(response_body):
            game_id = extract_request_param(path, raw_body, "g")
            user = extract_request_param(path, raw_body, "u")
            if game_id and user:
                self.storage.upsert_cache(
                    cache_keys.start_session(game_id, user), response_body
                )
                self.refresh_unlocks_from_start_session(
                    int(game_id), user, response_body
                )

        if should_cache_response(response_body) and should_cache_action(action, path):
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
                        self.storage,
                    )
        return response_bytes(status_code, response_body, reason)

    def refresh_unlocks_from_start_session(
        self, game_id: int, user: str, response_body: str
    ) -> None:
        try:
            payload = json.loads(response_body)
        except Exception:
            return

        unlock_entries = payload.get("Unlocks")
        if not isinstance(unlock_entries, Sequence) or isinstance(
            unlock_entries, (str, bytes, bytearray)
        ):
            return

        unlock_ids: list[int] = []
        for item in unlock_entries:
            if not isinstance(item, dict):
                continue
            achievement_id = item.get("ID")
            if isinstance(achievement_id, int) and achievement_id > 0:
                unlock_ids.append(achievement_id)

        unlock_ids = filter_warning_achievement_ids(unlock_ids)

        self.storage.upsert_cache(
            cache_keys.unlocks(game_id, user),
            json.dumps(
                {"Success": True, "UserUnlocks": unlock_ids},
                separators=(",", ":"),
            ),
        )

    def handle_offline_request(
        self, path: str, raw_body: str, action: str | None
    ) -> bytes:
        if not should_cache_action(action, path):
            return error_json(503, "offline")

        if action == "unlocks":
            game_id = extract_request_param(path, raw_body, "g")
            user = extract_request_param(path, raw_body, "u")
            if game_id and user:
                return ok_json(self.build_offline_unlocks_response(int(game_id), user))

        key = cache_key_for_request(path, raw_body)
        cached = self.storage.get_cache(key) or self.storage.get_cache_by_prefix(
            f"{key}:"
        )
        if cached is not None:
            return ok_json(cached["responseBody"])

        if action == "gameid":
            LOGGER.warning(
                "Offline gameid cache miss requestedKey=%s sampleKeys=%s",
                key,
                [
                    entry["cacheKey"]
                    for entry in self.storage.get_all_cache_by_prefix(
                        cache_keys.PREFIX_GAMEID
                    )[:10]
                ],
            )
            return game_id_cache_miss()

        if action == "achievementsets":
            hash_value = extract_request_param(path, raw_body, "m")
            fallback_game_id = extract_request_param(
                path, raw_body, "g"
            ) or extract_request_param(path, raw_body, "i")
            user = extract_request_param(path, raw_body, "u") or ""
            if hash_value and user:
                cached = self.storage.get_cache(
                    cache_keys.achievementsets(hash_value, user)
                )
                if cached is not None:
                    return ok_json(cached["responseBody"])
            if fallback_game_id and user:
                cached = self.storage.get_cache(
                    cache_keys.achievementsets(fallback_game_id, user)
                )
                if cached is not None:
                    return ok_json(cached["responseBody"])

        return error_json(503, "no cached response")

    def build_offline_unlocks_response(self, game_id: int, user: str) -> str:
        entry = self.storage.get_cache(cache_keys.unlocks(game_id, user))
        payload: dict = {"Success": True, "UserUnlocks": []}
        if entry is not None:
            try:
                payload = json.loads(entry["responseBody"])
            except Exception:
                payload = {"Success": True, "UserUnlocks": []}

        payload["Success"] = True
        payload["UserUnlocks"] = merged_unlock_ids(self.storage, game_id, user)
        return json.dumps(payload, separators=(",", ":"))

    def build_offline_start_session_response(self, game_id: int, user: str) -> str:
        entry = self.storage.get_cache(cache_keys.start_session(game_id, user))
        server_now = int(time.time())
        payload: dict = {
            "Success": True,
            "ServerNow": server_now,
            "HardcoreUnlocks": [],
        }
        if entry is not None:
            try:
                payload = json.loads(entry["responseBody"])
            except Exception:
                payload = {
                    "Success": True,
                    "ServerNow": server_now,
                    "HardcoreUnlocks": [],
                }

        server_now = int(payload.get("ServerNow", server_now) or server_now)
        cached_start_session_unlock_ids = filter_warning_achievement_ids([
            int(item.get("ID", 0))
            for item in payload.get("Unlocks", [])
            if isinstance(item, dict) and int(item.get("ID", 0) or 0) > 0
        ])
        payload["Success"] = True
        payload.setdefault("HardcoreUnlocks", [])
        payload["Unlocks"] = self.build_merged_start_session_unlocks(
            game_id, user, server_now, cached_start_session_unlock_ids
        )
        return json.dumps(payload, separators=(",", ":"))

    def build_merged_start_session_unlocks(
        self,
        game_id: int,
        user: str,
        server_now: int,
        cached_start_session_unlock_ids: list[int],
    ) -> list[dict]:
        merged_ids = merged_unlock_ids(self.storage, game_id, user)
        if not merged_ids:
            merged_ids = list(cached_start_session_unlock_ids)
        else:
            seen_ids = set(merged_ids)
            for achievement_id in cached_start_session_unlock_ids:
                if achievement_id <= 0 or achievement_id in seen_ids:
                    continue
                merged_ids.append(achievement_id)
                seen_ids.add(achievement_id)

        return [
            {"ID": achievement_id, "When": server_now} for achievement_id in merged_ids
        ]

    def forward_to_upstream(
        self, method: str, path: str, raw_body: str, headers: dict[str, str]
    ) -> bytes:
        upstream = self.forward_to_upstream_result(method, path, raw_body, headers)
        if upstream[0] == "success":
            return raw_response_bytes(
                upstream[1], upstream[3], upstream[4], upstream[2]
            )
        if upstream[0] == "http_error":
            return raw_response_bytes(
                upstream[1], upstream[3], upstream[4], upstream[2]
            )
        return error_json(503, "upstream unavailable")

    def forward_to_upstream_result(
        self, method: str, path: str, raw_body: str, headers: dict[str, str]
    ) -> tuple[str, int, str, bytes, str | None, str | None]:
        url = f"{upstream_host(self.config_data)}{path}"
        request_headers = build_forward_headers(headers)
        try:
            if method == "POST":
                status, reason, response_body = http_post(
                    url, raw_body, request_headers
                )
                response_bytes_body = response_body.encode("utf-8")
                content_type = "application/json"
            else:
                import urllib.request

                request = urllib.request.Request(
                    url, headers=request_headers, method="GET"
                )
                try:
                    with urllib.request.urlopen(request, timeout=15) as response:
                        status = response.status
                        reason = response.reason
                        response_bytes_body = read_response_bytes(response)
                        content_type = response_content_type(response)
                        if path.startswith("/dorequest.php"):
                            response_body = decode_response_body(
                                response_bytes_body, content_type
                            )
                        else:
                            response_body = ""
                except Exception as error:
                    if hasattr(error, "read"):
                        status = getattr(error, "code", 500)
                        reason = getattr(
                            error, "reason", canonical_reason_phrase(status)
                        )
                        response_bytes_body = error.read()
                        content_type = getattr(error, "headers", {}).get("Content-Type")
                        if path.startswith("/dorequest.php"):
                            response_body = decode_response_body(
                                response_bytes_body, content_type
                            )
                        else:
                            response_body = ""
                    else:
                        raise

            if 200 <= status < 300:
                return (
                    "success",
                    status,
                    reason,
                    response_bytes_body,
                    content_type,
                    response_body,
                )
            return (
                "http_error",
                status,
                reason,
                response_bytes_body,
                content_type,
                response_body,
            )
        except Exception as error:
            mark_retroachievements_unreachable()
            LOGGER.error("Upstream request failed: %s", error)
            return (
                "network_error",
                503,
                "Service Unavailable",
                json.dumps({"Success": False, "Error": str(error)}).encode("utf-8"),
                "application/json",
                json.dumps({"Success": False, "Error": str(error)}),
            )

    def queue_award(self, path: str, raw_body: str, headers: dict[str, str]) -> bool:
        achievement_id = int(extract_request_param(path, raw_body, "a") or "0")
        if achievement_id > 0 and self.storage.pending_award_exists(achievement_id):
            LOGGER.info(
                "Offline award already queued: achievementId=%s",
                achievement_id,
            )
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
            "status": "pending",
            "payloadHash": payload_hash,
            "prevHash": prev_hash,
            "signature": signature,
            "signedAt": current_millis(),
        }
        self.storage.upsert_pending_award(award)
        LOGGER.info(
            "Queued offline award: achievementId=%s queuedAt=%s",
            achievement_id,
            queued_at,
        )
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
            if outcome.total:
                LOGGER.info(
                    "Flush outcome: total=%s flushed=%s skipped_deleted=%s skipped_stale=%s pending_remaining=%s last_error=%s",
                    outcome.total,
                    outcome.flushed,
                    outcome.skipped_deleted,
                    outcome.skipped_stale,
                    outcome.pending_remaining,
                    outcome.last_error,
                )
            return {
                "flushed": outcome.flushed,
                "total": outcome.total,
                "skipped_deleted": outcome.skipped_deleted,
                "skipped_stale": outcome.skipped_stale,
                "pending_remaining": outcome.pending_remaining,
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
        was_online = self.server.refresh_reachability(force_probe=True)
        while not self.stop_event.wait(self.interval_seconds):
            is_online = self.server.refresh_reachability(force_probe=True)
            if is_online and not was_online:
                LOGGER.info("Connectivity restored; attempting flush")
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
            user_agent = self.server.storage.load_user_agent(FALLBACK_USER_AGENT)
            credentials = resolve_credentials(
                self.server.storage,
                self.server.config_data,
                user_agent,
            )
            if credentials is None:
                continue
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
                        game_id,
                        credentials,
                        user_agent,
                        self.server.config_data,
                        self.server.storage,
                    )
                    cache_session(game_id, credentials, self.server.storage)
                    game_ids.append(game_id)
            before = current_millis() - (self.cache_ttl_seconds * 1000)
            self.server.storage.evict_cache_older_than(before)


def run_proxy_service(
    config_data: dict, stop_event: threading.Event | None = None
) -> None:
    storage = Storage()
    server = ProxyRuntimeServer(config_data, storage)
    connectivity_monitor = ConnectivityMonitor(server)
    periodic_refresh = PeriodicRefresh(server)

    try:
        if server.refresh_reachability(force_probe=True):
            server.flush_pending_awards()
        connectivity_monitor.start()
        periodic_refresh.start()

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
        connectivity_monitor.stop()
        periodic_refresh.stop()
        server.server_close()
        storage.close()
