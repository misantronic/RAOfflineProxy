import json
import threading
import time
from pathlib import Path
from typing import Any

from . import cache_keys
from .config import DATABASE_FILE, ensure_config_dir

try:
    import sqlite3
except ModuleNotFoundError:
    sqlite3 = None

JSON_STORE_FILE = DATABASE_FILE.with_suffix(".json")

PENDING_AWARD_STATUS_PENDING = "pending"
PENDING_AWARD_STATUS_DELETED = "deleted"
PENDING_AWARD_STATUS_STALE = "stale"
PENDING_AWARD_STATUS_FLUSHED = "flushed"


class Storage:
    def __init__(self, database_path: Path = DATABASE_FILE):
        ensure_config_dir()
        self._database_path = database_path
        self._lock = threading.RLock()
        self._use_sqlite = sqlite3 is not None
        self._json_path = JSON_STORE_FILE
        self._json_state: dict[str, Any] | None = None
        self._connection = None

        if self._use_sqlite:
            self._connection = sqlite3.connect(
                self._database_path, check_same_thread=False
            )
            self._connection.row_factory = sqlite3.Row
            self._initialize_sqlite()
        else:
            self._initialize_json()

    def close(self) -> None:
        with self._lock:
            if self._connection is not None:
                self._connection.close()

    def _initialize_sqlite(self) -> None:
        assert self._connection is not None
        with self._lock:
            self._connection.executescript(
                """
                PRAGMA journal_mode=WAL;
                CREATE TABLE IF NOT EXISTS api_cache (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    cacheKey TEXT NOT NULL UNIQUE,
                    responseBody TEXT NOT NULL,
                    cachedAt INTEGER NOT NULL,
                    firstCachedAt INTEGER NOT NULL
                );
                CREATE TABLE IF NOT EXISTS pending_awards (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    achievementId INTEGER NOT NULL UNIQUE,
                    queryString TEXT NOT NULL,
                    requestBody TEXT NOT NULL,
                    userAgent TEXT NOT NULL,
                    queuedAt INTEGER NOT NULL,
                    retryCount INTEGER NOT NULL DEFAULT 0,
                    lastError TEXT,
                    status TEXT NOT NULL DEFAULT 'pending',
                    payloadHash TEXT NOT NULL DEFAULT '',
                    prevHash TEXT NOT NULL DEFAULT '',
                    signature TEXT NOT NULL DEFAULT '',
                    signedAt INTEGER NOT NULL DEFAULT 0
                );
                """
            )
            columns = {
                row["name"]
                for row in self._connection.execute(
                    "PRAGMA table_info(pending_awards)"
                ).fetchall()
            }
            if "status" not in columns:
                self._connection.execute(
                    "ALTER TABLE pending_awards ADD COLUMN status TEXT NOT NULL DEFAULT 'pending'"
                )
            self._connection.commit()

    def _initialize_json(self) -> None:
        with self._lock:
            if self._json_path.exists():
                with self._json_path.open(encoding="utf-8") as handle:
                    data = json.load(handle)
                if not isinstance(data, dict):
                    raise ValueError(f"Invalid JSON storage file: {self._json_path}")
                self._json_state = data
            else:
                self._json_state = {"api_cache": [], "pending_awards": []}
                self._write_json_state()

            self._json_state.setdefault("api_cache", [])
            self._json_state.setdefault("pending_awards", [])

    def _write_json_state(self) -> None:
        assert self._json_state is not None
        with self._json_path.open("w", encoding="utf-8") as handle:
            json.dump(self._json_state, handle, indent=2, sort_keys=True)
            handle.write("\n")

    def upsert_cache(
        self, cache_key: str, response_body: str, cached_at: int | None = None
    ) -> None:
        now = cached_at or current_millis()
        if self._use_sqlite:
            self._upsert_cache_sqlite(cache_key, response_body, now)
            return
        self._upsert_cache_json(cache_key, response_body, now)

    def _upsert_cache_sqlite(
        self, cache_key: str, response_body: str, now: int
    ) -> None:
        assert self._connection is not None
        with self._lock:
            row = self._connection.execute(
                "SELECT firstCachedAt FROM api_cache WHERE cacheKey = ? LIMIT 1",
                (cache_key,),
            ).fetchone()
            first_cached_at = int(row["firstCachedAt"]) if row is not None else now
            self._connection.execute(
                """
                INSERT INTO api_cache(cacheKey, responseBody, cachedAt, firstCachedAt)
                VALUES(?, ?, ?, ?)
                ON CONFLICT(cacheKey) DO UPDATE SET
                    responseBody = excluded.responseBody,
                    cachedAt = excluded.cachedAt
                """,
                (cache_key, response_body, now, first_cached_at),
            )
            self._connection.commit()

    def _upsert_cache_json(self, cache_key: str, response_body: str, now: int) -> None:
        assert self._json_state is not None
        with self._lock:
            existing = next(
                (
                    entry
                    for entry in self._json_state["api_cache"]
                    if entry["cacheKey"] == cache_key
                ),
                None,
            )
            if existing is None:
                self._json_state["api_cache"].append(
                    {
                        "id": next_json_id(self._json_state["api_cache"]),
                        "cacheKey": cache_key,
                        "responseBody": response_body,
                        "cachedAt": now,
                        "firstCachedAt": now,
                    }
                )
            else:
                existing["responseBody"] = response_body
                existing["cachedAt"] = now
            self._write_json_state()

    def get_cache(self, cache_key: str) -> dict | None:
        if self._use_sqlite:
            assert self._connection is not None
            with self._lock:
                row = self._connection.execute(
                    "SELECT * FROM api_cache WHERE cacheKey = ? LIMIT 1",
                    (cache_key,),
                ).fetchone()
            return row_to_dict(row)

        assert self._json_state is not None
        with self._lock:
            entry = next(
                (
                    item
                    for item in self._json_state["api_cache"]
                    if item["cacheKey"] == cache_key
                ),
                None,
            )
            return dict(entry) if entry is not None else None

    def get_cache_by_prefix(self, prefix: str) -> dict | None:
        if self._use_sqlite:
            assert self._connection is not None
            with self._lock:
                row = self._connection.execute(
                    "SELECT * FROM api_cache WHERE cacheKey LIKE ? LIMIT 1",
                    (f"{prefix}%",),
                ).fetchone()
            return row_to_dict(row)

        assert self._json_state is not None
        with self._lock:
            entry = next(
                (
                    item
                    for item in self._json_state["api_cache"]
                    if item["cacheKey"].startswith(prefix)
                ),
                None,
            )
            return dict(entry) if entry is not None else None

    def get_all_cache_by_prefix(self, prefix: str) -> list[dict]:
        if self._use_sqlite:
            assert self._connection is not None
            with self._lock:
                rows = self._connection.execute(
                    "SELECT * FROM api_cache WHERE cacheKey LIKE ? ORDER BY cachedAt DESC",
                    (f"{prefix}%",),
                ).fetchall()
            return [row_to_dict(row) for row in rows]

        assert self._json_state is not None
        with self._lock:
            matches = [
                dict(item)
                for item in self._json_state["api_cache"]
                if item["cacheKey"].startswith(prefix)
            ]
        return sorted(matches, key=lambda item: item.get("cachedAt", 0), reverse=True)

    def delete_cache_by_prefix(self, prefix: str) -> None:
        if self._use_sqlite:
            assert self._connection is not None
            with self._lock:
                self._connection.execute(
                    "DELETE FROM api_cache WHERE cacheKey LIKE ?", (f"{prefix}%",)
                )
                self._connection.commit()
            return

        assert self._json_state is not None
        with self._lock:
            self._json_state["api_cache"] = [
                item
                for item in self._json_state["api_cache"]
                if not item["cacheKey"].startswith(prefix)
            ]
            self._write_json_state()

    def evict_cache_older_than(self, before: int) -> None:
        if self._use_sqlite:
            assert self._connection is not None
            with self._lock:
                self._connection.execute(
                    """
                    DELETE FROM api_cache
                    WHERE cachedAt < ?
                      AND cacheKey NOT LIKE 'login2::%'
                      AND cacheKey != ?
                    """,
                    (before, cache_keys.USER_AGENT),
                )
                self._connection.commit()
            return

        assert self._json_state is not None
        with self._lock:
            self._json_state["api_cache"] = [
                item
                for item in self._json_state["api_cache"]
                if item["cachedAt"] >= before
                or item["cacheKey"].startswith(cache_keys.PREFIX_LOGIN)
                or item["cacheKey"] == cache_keys.USER_AGENT
            ]
            self._write_json_state()

    def get_pending_awards(self) -> list[dict]:
        if self._use_sqlite:
            assert self._connection is not None
            with self._lock:
                rows = self._connection.execute(
                    "SELECT * FROM pending_awards ORDER BY queuedAt ASC"
                ).fetchall()
            return [row_to_dict(row) for row in rows]

        assert self._json_state is not None
        with self._lock:
            awards = [dict(item) for item in self._json_state["pending_awards"]]
        return sorted(awards, key=lambda item: item.get("queuedAt", 0))

    def get_latest_pending_award(self) -> dict | None:
        awards = self.get_pending_awards()
        return awards[-1] if awards else None

    def pending_award_exists(self, achievement_id: int) -> bool:
        if self._use_sqlite:
            assert self._connection is not None
            with self._lock:
                row = self._connection.execute(
                    "SELECT 1 FROM pending_awards WHERE achievementId = ? LIMIT 1",
                    (achievement_id,),
                ).fetchone()
            return row is not None

        assert self._json_state is not None
        with self._lock:
            return any(
                item["achievementId"] == achievement_id
                for item in self._json_state["pending_awards"]
            )

    def upsert_pending_award(self, award: dict) -> None:
        if self._use_sqlite:
            self._upsert_pending_award_sqlite(award)
            return
        self._upsert_pending_award_json(award)

    def _upsert_pending_award_sqlite(self, award: dict) -> None:
        assert self._connection is not None
        with self._lock:
            self._connection.execute(
                """
                INSERT INTO pending_awards(
                    achievementId,
                    queryString,
                    requestBody,
                    userAgent,
                    queuedAt,
                    retryCount,
                    lastError,
                    status,
                    payloadHash,
                    prevHash,
                    signature,
                    signedAt
                ) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(achievementId) DO UPDATE SET
                    queryString = excluded.queryString,
                    requestBody = excluded.requestBody,
                    userAgent = excluded.userAgent,
                    queuedAt = excluded.queuedAt,
                    retryCount = excluded.retryCount,
                    lastError = excluded.lastError,
                    status = excluded.status,
                    payloadHash = excluded.payloadHash,
                    prevHash = excluded.prevHash,
                    signature = excluded.signature,
                    signedAt = excluded.signedAt
                """,
                (
                    award["achievementId"],
                    award["queryString"],
                    award["requestBody"],
                    award["userAgent"],
                    award["queuedAt"],
                    award.get("retryCount", 0),
                    award.get("lastError"),
                    award.get("status", PENDING_AWARD_STATUS_PENDING),
                    award.get("payloadHash", ""),
                    award.get("prevHash", ""),
                    award.get("signature", ""),
                    award.get("signedAt", 0),
                ),
            )
            self._connection.commit()

    def _upsert_pending_award_json(self, award: dict) -> None:
        assert self._json_state is not None
        with self._lock:
            existing = next(
                (
                    item
                    for item in self._json_state["pending_awards"]
                    if item["achievementId"] == award["achievementId"]
                ),
                None,
            )
            materialized = dict(award)
            materialized.setdefault(
                "id", next_json_id(self._json_state["pending_awards"])
            )
            materialized.setdefault("retryCount", 0)
            materialized.setdefault("lastError", None)
            materialized.setdefault("status", PENDING_AWARD_STATUS_PENDING)
            materialized.setdefault("payloadHash", "")
            materialized.setdefault("prevHash", "")
            materialized.setdefault("signature", "")
            materialized.setdefault("signedAt", 0)
            if existing is None:
                self._json_state["pending_awards"].append(materialized)
            else:
                materialized["id"] = existing.get("id", materialized["id"])
                existing.update(materialized)
            self._write_json_state()

    def update_pending_award(self, award: dict) -> None:
        self.upsert_pending_award(award)

    def delete_pending_award(self, achievement_id: int) -> None:
        if self._use_sqlite:
            assert self._connection is not None
            with self._lock:
                self._connection.execute(
                    "DELETE FROM pending_awards WHERE achievementId = ?",
                    (achievement_id,),
                )
                self._connection.commit()
            return

        assert self._json_state is not None
        with self._lock:
            self._json_state["pending_awards"] = [
                item
                for item in self._json_state["pending_awards"]
                if item["achievementId"] != achievement_id
            ]
            self._write_json_state()

    def pending_awards_exist_by_status(self, status: str) -> bool:
        if self._use_sqlite:
            assert self._connection is not None
            with self._lock:
                row = self._connection.execute(
                    "SELECT 1 FROM pending_awards WHERE status = ? LIMIT 1",
                    (status,),
                ).fetchone()
            return row is not None

        assert self._json_state is not None
        with self._lock:
            return any(
                item.get("status", PENDING_AWARD_STATUS_PENDING) == status
                for item in self._json_state["pending_awards"]
            )

    def delete_pending_awards_by_statuses(self, statuses: list[str]) -> None:
        if self._use_sqlite:
            assert self._connection is not None
            placeholders = ",".join("?" for _ in statuses)
            with self._lock:
                self._connection.execute(
                    f"DELETE FROM pending_awards WHERE status IN ({placeholders})",
                    tuple(statuses),
                )
                self._connection.commit()
            return

        assert self._json_state is not None
        with self._lock:
            status_set = set(statuses)
            self._json_state["pending_awards"] = [
                item
                for item in self._json_state["pending_awards"]
                if item.get("status", PENDING_AWARD_STATUS_PENDING) not in status_set
            ]
            self._write_json_state()

    def load_login_credentials(self) -> dict | None:
        entry = self.get_cache_by_prefix(cache_keys.PREFIX_LOGIN)
        if entry is None:
            return None

        try:
            payload = json.loads(entry["responseBody"])
        except json.JSONDecodeError:
            return None

        user = payload.get("User")
        token = payload.get("Token")
        if not user or not token:
            return None
        return {"user": user, "token": token}

    def load_user_agent(self, fallback: str) -> str:
        entry = self.get_cache(cache_keys.USER_AGENT)
        if entry is None:
            return fallback
        user_agent = entry.get("responseBody", "")
        return user_agent if user_agent else fallback


def current_millis() -> int:
    return int(time.time() * 1000)


def next_json_id(items: list[dict]) -> int:
    if not items:
        return 1
    return max(int(item.get("id", 0)) for item in items) + 1


def row_to_dict(row: Any | None) -> dict | None:
    if row is None:
        return None
    return dict(row)
