from __future__ import annotations

import hashlib
import json
import re
import threading
import time
from pathlib import Path

FIXTURES_DIR = Path(__file__).resolve().parent / "fixtures"

MODE_ONLINE = "online"
MODE_OFFLINE = "offline"
MODE_DEGRADED = "degraded"
MODE_SLOW = "slow"
MODES = (MODE_ONLINE, MODE_OFFLINE, MODE_DEGRADED, MODE_SLOW)

MEDIA_HOST = "https://media.retroachievements.org"

ALLOWED_CLIENTS = (
    "RetroArch",
    "RALibretro",
    "Dolphin",
    "PPSSPP",
    "PCSX2",
    "ARMSX2",
    "melonDS",
    "DuckStation",
)

VERSION_PATTERN = re.compile(r"^\d+(\.\d+)*")


def compute_validation_hash(
    achievement_id: int, username: str, hardcore: int, seconds_since_unlock: int
) -> str:
    md5 = hashlib.md5()
    aid = str(achievement_id)
    md5.update(aid.encode("utf-8"))
    md5.update(username.encode("utf-8"))
    md5.update(str(hardcore).encode("utf-8"))
    if seconds_since_unlock:
        md5.update(aid.encode("utf-8"))
        md5.update(str(seconds_since_unlock).encode("utf-8"))
    return md5.hexdigest()


def classify_user_agent(user_agent: str) -> tuple[bool, str]:
    if not user_agent or not user_agent.strip():
        return False, "missing user agent"

    first_token = user_agent.strip().split(" ", 1)[0]
    if "/" not in first_token:
        return False, "client identity carries no version"

    client, _, version = first_token.partition("/")
    if client not in ALLOWED_CLIENTS:
        return False, "unknown client " + client
    if not VERSION_PATTERN.match(version):
        return False, "unparseable version " + version
    return True, ""


def _load_fixture(name: str) -> dict:
    with (FIXTURES_DIR / name).open(encoding="utf-8") as handle:
        return json.load(handle)


class RaState:
    def __init__(self, persist_path: Path | None = None) -> None:
        self._lock = threading.RLock()
        self._persist_path = persist_path
        self.mode = MODE_ONLINE
        self.slow_delay_seconds = 3.0
        self.enforce_user_agent = True
        self.journal: list[dict] = []
        self.violations: list[dict] = []
        self._load_fixtures()
        self._restore()

    def _load_fixtures(self) -> None:
        self.users = _load_fixture("users.json")
        self.games = {
            key.strip().lower(): value
            for key, value in _load_fixture("games.json").items()
        }
        self.sets = {int(key): value for key, value in _load_fixture("sets.json").items()}
        self.unlocks: dict[str, dict[int, dict[int, int]]] = {}

    def _restore(self) -> None:
        if self._persist_path is None or not self._persist_path.exists():
            return
        try:
            with self._persist_path.open(encoding="utf-8") as handle:
                saved = json.load(handle)
        except Exception:
            return
        self.unlocks = {
            user: {
                int(game_id): {int(aid): int(when) for aid, when in entries.items()}
                for game_id, entries in games.items()
            }
            for user, games in saved.get("unlocks", {}).items()
        }
        for name, token in saved.get("tokens", {}).items():
            if name in self.users:
                self.users[name]["token"] = token

    def _persist(self) -> None:
        if self._persist_path is None:
            return
        payload = {
            "unlocks": {
                user: {
                    str(game_id): {str(aid): when for aid, when in entries.items()}
                    for game_id, entries in games.items()
                }
                for user, games in self.unlocks.items()
            },
            "tokens": {name: data["token"] for name, data in self.users.items()},
        }
        self._persist_path.parent.mkdir(parents=True, exist_ok=True)
        with self._persist_path.open("w", encoding="utf-8") as handle:
            json.dump(payload, handle, indent=2, sort_keys=True)

    def set_mode(self, mode: str) -> None:
        if mode not in MODES:
            raise ValueError("unknown mode " + mode)
        with self._lock:
            self.mode = mode

    def normalize_user(self, user: str) -> str:
        return (user or "").strip().lower()

    def find_user(self, user: str) -> dict | None:
        return self.users.get(self.normalize_user(user))

    def token_valid(self, user: str, token: str) -> bool:
        record = self.find_user(user)
        return bool(record and token and record["token"] == token)

    def rotate_token(self, user: str) -> str | None:
        with self._lock:
            record = self.find_user(user)
            if record is None:
                return None
            record["token"] = "tok-" + hashlib.sha256(
                (record["token"] + str(time.time())).encode("utf-8")
            ).hexdigest()[:24]
            self._persist()
            return record["token"]

    def game_id_for_hash(self, hash_value: str) -> int:
        return int(self.games.get((hash_value or "").strip().lower(), 0))

    def achievement_set(self, game_id: int) -> dict | None:
        return self.sets.get(int(game_id))

    def game_id_for_achievement(self, achievement_id: int) -> int | None:
        for game_id, game in self.sets.items():
            for achievement in game.get("Achievements", []):
                if int(achievement.get("ID", 0)) == int(achievement_id):
                    return game_id
        return None

    def user_unlocks(self, user: str, game_id: int) -> dict[int, int]:
        return self.unlocks.get(self.normalize_user(user), {}).get(int(game_id), {})

    def unlock(self, user: str, game_id: int, achievement_id: int, when: int) -> bool:
        with self._lock:
            key = self.normalize_user(user)
            games = self.unlocks.setdefault(key, {})
            entries = games.setdefault(int(game_id), {})
            already = int(achievement_id) in entries
            if not already:
                entries[int(achievement_id)] = int(when)
                achievement = self._find_achievement(game_id, achievement_id)
                if achievement is not None and key in self.users:
                    self.users[key]["score"] += int(achievement.get("Points", 0))
                self._persist()
            return not already

    def _find_achievement(self, game_id: int, achievement_id: int) -> dict | None:
        game = self.achievement_set(game_id)
        if game is None:
            return None
        for achievement in game.get("Achievements", []):
            if int(achievement.get("ID", 0)) == int(achievement_id):
                return achievement
        return None

    def reset_progress(self, user: str | None = None, game_id: int | None = None) -> None:
        with self._lock:
            if user is None:
                self.unlocks = {}
            else:
                key = self.normalize_user(user)
                if game_id is None:
                    self.unlocks.pop(key, None)
                else:
                    self.unlocks.get(key, {}).pop(int(game_id), None)
            self._load_score_from_unlocks()
            self._persist()

    def _load_score_from_unlocks(self) -> None:
        for name, record in self.users.items():
            score = 0
            for game_id, entries in self.unlocks.get(name, {}).items():
                for achievement_id in entries:
                    achievement = self._find_achievement(game_id, achievement_id)
                    if achievement is not None:
                        score += int(achievement.get("Points", 0))
            record["score"] = score

    def record_request(self, entry: dict) -> None:
        with self._lock:
            self.journal.append(entry)

    def record_violation(self, kind: str, detail: str, entry: dict) -> None:
        with self._lock:
            self.violations.append(
                {
                    "kind": kind,
                    "detail": detail,
                    "action": entry.get("action"),
                    "path": entry.get("path"),
                    "body": entry.get("body"),
                    "userAgent": entry.get("userAgent"),
                    "at": time.time(),
                }
            )

    def clear_journal(self) -> None:
        with self._lock:
            self.journal = []
            self.violations = []
