from __future__ import annotations

import base64
import json
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qsl, urlsplit

from .state import (
    MODE_DEGRADED,
    MODE_OFFLINE,
    MODE_SLOW,
    RaState,
    classify_user_agent,
    compute_validation_hash,
)

IMAGE_PREFIXES = ("/Badge/", "/Images/", "/UserPic/")

PIXEL_PNG = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk"
    "+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="
)

MAX_BODY_BYTES = 1 << 20


def merged_params(path: str, body: str) -> dict:
    query = dict(parse_qsl(urlsplit(path).query, keep_blank_values=True))
    form = dict(parse_qsl(body, keep_blank_values=True))
    merged = dict(form)
    for key, value in query.items():
        if value or key not in merged:
            merged[key] = value
    return merged


def json_bytes(payload: dict) -> bytes:
    return json.dumps(payload, separators=(",", ":")).encode("utf-8")


class _JsonHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    state: RaState

    def log_message(self, format: str, *args) -> None:
        pass

    def read_body(self) -> str:
        length = int(self.headers.get("Content-Length") or 0)
        if length <= 0:
            return ""
        if length > MAX_BODY_BYTES:
            return ""
        return self.rfile.read(length).decode("utf-8", errors="replace")

    def send_json(self, code: int, payload: dict) -> None:
        self.send_payload(code, json_bytes(payload), "application/json")

    def send_payload(self, code: int, body: bytes, content_type: str) -> None:
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if body:
            self.wfile.write(body)


class RaHandler(_JsonHandler):
    def _blocked(self) -> bool:
        if self.state.mode == MODE_OFFLINE:
            self.close_connection = True
            try:
                self.connection.close()
            except OSError:
                pass
            return True
        if self.state.mode == MODE_SLOW:
            time.sleep(self.state.slow_delay_seconds)
        return False

    def do_HEAD(self) -> None:
        if self._blocked():
            return
        if self.state.mode == MODE_DEGRADED:
            self.send_payload(503, b"", "text/plain")
            return
        self.send_payload(200, b"", "text/html")

    def do_GET(self) -> None:
        self._dispatch("GET")

    def do_POST(self) -> None:
        self._dispatch("POST")

    def _dispatch(self, method: str) -> None:
        if self._blocked():
            return

        path = self.path
        body = self.read_body()

        if any(path.startswith(prefix) for prefix in IMAGE_PREFIXES):
            self.state.record_request(
                {"method": method, "path": path, "action": "image", "at": time.time()}
            )
            if self.state.mode == MODE_DEGRADED:
                self.send_payload(503, b"", "text/plain")
                return
            self.send_payload(200, PIXEL_PNG, "image/png")
            return

        if not path.startswith("/dorequest.php"):
            self.send_payload(404, b"", "text/plain")
            return

        if self.state.mode == MODE_DEGRADED:
            self.send_json(503, {"Success": False, "Error": "Service Unavailable"})
            return

        params = merged_params(path, body)
        action = params.get("r") or ""
        user_agent = self.headers.get("User-Agent") or ""

        entry = {
            "method": method,
            "path": path,
            "body": body,
            "action": action,
            "params": {k: v for k, v in params.items() if k not in ("p", "t")},
            "userAgent": user_agent,
            "at": time.time(),
        }
        self.state.record_request(entry)

        if params.get("h") == "1":
            self.state.record_violation(
                "hardcore_request",
                "request carried h=1, which must never leave the proxy",
                entry,
            )

        if self.state.enforce_user_agent:
            allowed, reason = classify_user_agent(user_agent)
            if not allowed:
                self.state.record_violation("user_agent", reason, entry)
                self.send_json(
                    403, {"Success": False, "Error": "unsupported_client", "Code": "unsupported_client"}
                )
                return

        handler = {
            "login": self._action_login,
            "login2": self._action_login,
            "gameid": self._action_gameid,
            "patch": self._action_patch,
            "achievementsets": self._action_achievementsets,
            "unlocks": self._action_unlocks,
            "startsession": self._action_startsession,
            "ping": self._action_ok,
            "postactivity": self._action_ok,
            "awardachievement": self._action_award,
            "submitlbentry": self._action_submit_lb,
        }.get(action)

        if handler is None:
            self.send_json(200, {"Success": False, "Error": "Unknown request type"})
            return

        handler(params, entry)

    def _require_token(self, params: dict) -> bool:
        user = params.get("u") or ""
        token = params.get("t") or ""
        if self.state.token_valid(user, token):
            return True
        self.send_json(
            401,
            {
                "Success": False,
                "Error": "Invalid token, please log in again.",
                "Code": "invalid_credentials",
            },
        )
        return False

    def _action_ok(self, params: dict, entry: dict) -> None:
        self.send_json(200, {"Success": True})

    def _action_login(self, params: dict, entry: dict) -> None:
        user = params.get("u") or ""
        record = self.state.find_user(user)
        password = params.get("p")
        token = params.get("t")

        authenticated = record is not None and (
            (password is not None and password == record["password"])
            or (token is not None and token == record["token"])
        )
        if record is None or not authenticated:
            self.send_json(
                200,
                {
                    "Success": False,
                    "Error": "Invalid user/password combination. Please try again.",
                    "Code": "invalid_credentials",
                },
            )
            return

        name = self.state.normalize_user(user)
        self.send_json(
            200,
            {
                "Success": True,
                "User": name,
                "Token": record["token"],
                "Score": record["score"],
                "SoftcoreScore": record["score"],
                "Messages": 0,
                "Permissions": 1,
                "AccountType": "Registered",
                "AvatarUrl": record["avatar"],
            },
        )

    def _action_gameid(self, params: dict, entry: dict) -> None:
        game_id = self.state.game_id_for_hash(params.get("m") or "")
        self.send_json(200, {"Success": True, "GameID": game_id})

    def _action_patch(self, params: dict, entry: dict) -> None:
        if not self._require_token(params):
            return
        game_id = int(params.get("g") or 0)
        game = self.state.achievement_set(game_id)
        if game is None:
            self.send_json(200, {"Success": False, "Error": "Unknown game"})
            return
        self.send_json(
            200,
            {
                "Success": True,
                "PatchData": {
                    "ID": game_id,
                    "Title": game["Title"],
                    "ConsoleID": game["ConsoleID"],
                    "ImageIcon": game["ImageIcon"],
                    "RichPresencePatch": game.get("RichPresencePatch", ""),
                    "Achievements": game["Achievements"],
                    "Leaderboards": [],
                },
            },
        )

    def _action_achievementsets(self, params: dict, entry: dict) -> None:
        if not self._require_token(params):
            return
        game_id = int(params.get("g") or 0) or self.state.game_id_for_hash(
            params.get("m") or ""
        )
        game = self.state.achievement_set(game_id)
        if game is None:
            self.send_json(200, {"Success": False, "Error": "Unknown game"})
            return
        self.send_json(
            200,
            {
                "Success": True,
                "GameId": game_id,
                "Title": game["Title"],
                "ImageIcon": game["ImageIcon"],
                "ConsoleId": game["ConsoleID"],
                "Sets": [
                    {
                        "GameId": game_id,
                        "Title": game["Title"],
                        "Type": "core",
                        "ImageIcon": game["ImageIcon"],
                        "Achievements": game["Achievements"],
                    }
                ],
            },
        )

    def _action_unlocks(self, params: dict, entry: dict) -> None:
        if not self._require_token(params):
            return
        game_id = int(params.get("g") or 0)
        user = params.get("u") or ""
        unlocked = sorted(self.state.user_unlocks(user, game_id))
        self.send_json(
            200,
            {
                "Success": True,
                "GameID": game_id,
                "HardcoreMode": params.get("h") == "1",
                "UserUnlocks": unlocked,
            },
        )

    def _action_startsession(self, params: dict, entry: dict) -> None:
        if not self._require_token(params):
            return
        game_id = int(params.get("g") or 0)
        user = params.get("u") or ""
        entries = self.state.user_unlocks(user, game_id)
        self.send_json(
            200,
            {
                "Success": True,
                "ServerNow": int(time.time()),
                "Unlocks": [
                    {"ID": achievement_id, "When": when}
                    for achievement_id, when in sorted(entries.items())
                ],
                "HardcoreUnlocks": [],
            },
        )

    def _action_award(self, params: dict, entry: dict) -> None:
        if not self._require_token(params):
            return

        user = params.get("u") or ""
        achievement_id = int(params.get("a") or 0)
        hardcore = int(params.get("h") or 0)
        offset_seconds = int(params.get("o") or 0)
        supplied_hash = params.get("v")

        if supplied_hash:
            expected = compute_validation_hash(
                achievement_id, user, hardcore, offset_seconds
            )
            if supplied_hash != expected:
                self.state.record_violation(
                    "validation_hash",
                    "v=%s expected=%s (a=%s u=%s h=%s o=%s)"
                    % (supplied_hash, expected, achievement_id, user, hardcore, offset_seconds),
                    entry,
                )
                self.send_json(
                    200,
                    {"Success": False, "Error": "Achievement validation failed"},
                )
                return

        game_id = self.state.game_id_for_achievement(achievement_id)
        if game_id is None:
            self.send_json(200, {"Success": False, "Error": "Unknown achievement"})
            return

        newly_unlocked = self.state.unlock(
            user, game_id, achievement_id, int(time.time()) - offset_seconds
        )
        record = self.state.find_user(user)
        remaining = len(self.state.achievement_set(game_id)["Achievements"]) - len(
            self.state.user_unlocks(user, game_id)
        )
        self.send_json(
            200,
            {
                "Success": True,
                "AchievementID": achievement_id,
                "AchievementsRemaining": remaining,
                "Score": record["score"],
                "SoftcoreScore": record["score"],
                "NewlyUnlocked": newly_unlocked,
            },
        )

    def _action_submit_lb(self, params: dict, entry: dict) -> None:
        if not self._require_token(params):
            return
        score = int(params.get("s") or 0)
        self.send_json(
            200,
            {
                "Success": True,
                "Score": score,
                "BestScore": score,
                "LBData": {"LeaderboardID": int(params.get("i") or 0)},
                "RankInfo": {"Rank": 1, "NumEntries": 1},
            },
        )


class CtlHandler(_JsonHandler):
    def do_GET(self) -> None:
        parsed = urlsplit(self.path)
        params = dict(parse_qsl(parsed.query, keep_blank_values=True))
        route = parsed.path

        if route == "/_ctl/health":
            self.send_json(200, {"ok": True, "mode": self.state.mode})
        elif route == "/_ctl/mode":
            self.send_json(200, {"mode": self.state.mode})
        elif route == "/_ctl/journal":
            action = params.get("action")
            journal = self.state.journal
            if action:
                journal = [e for e in journal if e.get("action") == action]
            self.send_json(200, {"requests": journal, "count": len(journal)})
        elif route == "/_ctl/violations":
            self.send_json(
                200,
                {"violations": self.state.violations, "count": len(self.state.violations)},
            )
        elif route == "/_ctl/unlocks":
            user = params.get("u") or ""
            if "g" in params:
                entries = self.state.user_unlocks(user, int(params["g"]))
                self.send_json(200, {"unlocks": sorted(entries), "when": entries})
            else:
                games = self.state.unlocks.get(self.state.normalize_user(user), {})
                self.send_json(
                    200,
                    {"unlocks": {str(g): sorted(e) for g, e in games.items()}},
                )
        elif route == "/_ctl/score":
            record = self.state.find_user(params.get("u") or "")
            self.send_json(200, {"score": record["score"] if record else 0})
        else:
            self.send_json(404, {"error": "unknown control route"})

    def do_POST(self) -> None:
        route = urlsplit(self.path).path
        raw = self.read_body()
        try:
            payload = json.loads(raw) if raw else {}
        except ValueError:
            self.send_json(400, {"error": "invalid json"})
            return

        if route == "/_ctl/mode":
            try:
                self.state.set_mode(payload.get("mode", ""))
            except ValueError as exc:
                self.send_json(400, {"error": str(exc)})
                return
            self.send_json(200, {"mode": self.state.mode})
        elif route == "/_ctl/reset":
            self.state.reset_progress(payload.get("user"), payload.get("game_id"))
            self.send_json(200, {"ok": True})
        elif route == "/_ctl/rotate-token":
            token = self.state.rotate_token(payload.get("user", ""))
            if token is None:
                self.send_json(404, {"error": "unknown user"})
                return
            self.send_json(200, {"ok": True})
        elif route == "/_ctl/map-hash":
            hash_value = str(payload.get("hash", "")).strip().lower()
            game_id = int(payload.get("game_id", 0))
            if not hash_value or game_id <= 0:
                self.send_json(400, {"error": "hash and game_id required"})
                return
            self.state.games[hash_value] = game_id
            self.send_json(200, {"ok": True, "hash": hash_value, "game_id": game_id})
        elif route == "/_ctl/clear-journal":
            self.state.clear_journal()
            self.send_json(200, {"ok": True})
        elif route == "/_ctl/user-agent-enforcement":
            self.state.enforce_user_agent = bool(payload.get("enabled", True))
            self.send_json(200, {"enabled": self.state.enforce_user_agent})
        elif route == "/_ctl/slow-delay":
            self.state.slow_delay_seconds = float(payload.get("seconds", 3.0))
            self.send_json(200, {"seconds": self.state.slow_delay_seconds})
        else:
            self.send_json(404, {"error": "unknown control route"})


def build_servers(
    state: RaState, host: str, ra_port: int, ctl_port: int
) -> tuple[ThreadingHTTPServer, ThreadingHTTPServer]:
    ra_handler = type("BoundRaHandler", (RaHandler,), {"state": state})
    ctl_handler = type("BoundCtlHandler", (CtlHandler,), {"state": state})
    ra_server = ThreadingHTTPServer((host, ra_port), ra_handler)
    ctl_server = ThreadingHTTPServer((host, ctl_port), ctl_handler)
    ra_server.daemon_threads = True
    ctl_server.daemon_threads = True
    return ra_server, ctl_server
