import json
import time

from . import cache_keys
from .config import FALLBACK_USER_AGENT, upstream_host
from .network import build_api_url, http_get
from .storage import Storage


def refresh_game_patch(
    game_id: int,
    credentials: dict,
    user_agent: str,
    storage: Storage,
    config_data: dict,
) -> str | None:
    url = build_api_url(
        upstream_host(config_data),
        "patch",
        {
            "g": str(game_id),
            "u": credentials["user"],
            "t": credentials["token"],
        },
    )

    try:
        response_body = http_get(url, user_agent)
        payload = json.loads(response_body)
    except Exception:
        return None

    if not payload.get("Success"):
        return None

    storage.upsert_cache(cache_keys.patch(game_id, credentials["user"]), response_body)
    return response_body


def cache_unlocks(
    game_id: int, credentials: dict, user_agent: str, config_data: dict
) -> str | None:
    url = build_api_url(
        upstream_host(config_data),
        "unlocks",
        {
            "g": str(game_id),
            "h": "0",
            "u": credentials["user"],
            "t": credentials["token"],
        },
    )
    try:
        response_body = http_get(url, user_agent)
        payload = json.loads(response_body)
    except Exception:
        return None

    if not payload.get("Success"):
        return None

    return response_body


def build_unlocks_array(
    storage: Storage, game_id: int, user: str, server_now: int
) -> list[dict]:
    entry = storage.get_cache(cache_keys.unlocks(game_id, user))
    if entry is None:
        return []

    try:
        payload = json.loads(entry["responseBody"])
        unlock_ids = payload.get("UserUnlocks") or []
        return [
            {"ID": achievement_id, "When": server_now} for achievement_id in unlock_ids
        ]
    except Exception:
        return []


def cache_session(game_id: int, credentials: dict, storage: Storage) -> str:
    server_now = int(time.time())
    payload = {
        "Success": True,
        "ServerNow": server_now,
        "HardcoreUnlocks": [],
        "Unlocks": build_unlocks_array(
            storage, game_id, credentials["user"], server_now
        ),
    }
    response_body = json.dumps(payload, separators=(",", ":"))
    storage.upsert_cache(
        cache_keys.start_session(game_id, credentials["user"]), response_body
    )
    return response_body


def cache_game(
    game_id: int,
    credentials: dict,
    user_agent: str,
    storage: Storage,
    config_data: dict,
) -> None:
    refresh_game_patch(
        game_id,
        credentials,
        user_agent or FALLBACK_USER_AGENT,
        storage,
        config_data,
    )

    unlocks_body = cache_unlocks(
        game_id,
        credentials,
        user_agent or FALLBACK_USER_AGENT,
        config_data,
    )
    if unlocks_body is not None:
        storage.upsert_cache(
            cache_keys.unlocks(game_id, credentials["user"]),
            unlocks_body,
        )

    cache_session(game_id, credentials, storage)
