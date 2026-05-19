import json
import logging
import time

from . import cache_keys
from .config import FALLBACK_USER_AGENT, image_caching_enabled, upstream_host
from .image_cache import cache_patch_images
from .network import build_api_url, http_get
from .storage import PENDING_AWARD_STATUS_PENDING, Storage
from .utils import is_hardcore_request, parse_form_params

LOGGER = logging.getLogger("raofflineproxy")


class CacheGameError(RuntimeError):
    pass


def api_error_message(action: str, payload: dict) -> str:
    message = payload.get("Error") or payload.get("Message") or payload.get("error")
    if message:
        return f"{action} failed: {message}"
    return f"{action} failed"


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
    except Exception as exc:
        raise CacheGameError(f"patch request failed: {exc}") from exc

    if not payload.get("Success"):
        raise CacheGameError(api_error_message("patch", payload))

    storage.upsert_cache(cache_keys.patch(game_id, credentials["user"]), response_body)
    if image_caching_enabled(config_data):
        cache_patch_images(game_id, user_agent, response_body, config_data)
    else:
        LOGGER.info("Cache game images skipped gameId=%s", game_id)
    return response_body


def cache_unlocks(
    game_id: int,
    credentials: dict,
    user_agent: str,
    config_data: dict,
    storage: Storage | None = None,
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

    if storage is not None:
        storage.upsert_cache(
            cache_keys.unlocks(game_id, credentials["user"]),
            response_body,
        )

    return response_body


def cache_achievementsets(
    hash_value: str,
    credentials: dict,
    user_agent: str,
    config_data: dict,
    storage: Storage | None = None,
) -> str | None:
    url = build_api_url(
        upstream_host(config_data),
        "achievementsets",
        {
            "m": hash_value,
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

    if storage is not None:
        storage.upsert_cache(
            cache_keys.achievementsets(hash_value, credentials["user"]),
            response_body,
        )

    return response_body


def build_unlocks_array(
    storage: Storage, game_id: int, user: str, server_now: int
) -> list[dict]:
    unlock_ids = merged_unlock_ids(storage, game_id, user)
    return [{"ID": achievement_id, "When": server_now} for achievement_id in unlock_ids]


def merged_unlock_ids(storage: Storage, game_id: int, user: str) -> list[int]:
    entry = storage.get_cache(cache_keys.unlocks(game_id, user))
    cached_unlock_ids: list[int] = []
    if entry is not None:
        try:
            payload = json.loads(entry["responseBody"])
            unlock_ids = payload.get("UserUnlocks") or []
            cached_unlock_ids = [
                achievement_id
                for achievement_id in unlock_ids
                if isinstance(achievement_id, int) and achievement_id > 0
            ]
        except Exception:
            cached_unlock_ids = []

    achievement_game_ids = build_achievement_game_ids(
        storage.get_all_cache_by_prefix(cache_keys.PREFIX_PATCH)
    )
    return merge_start_session_unlock_ids(
        cached_unlock_ids=cached_unlock_ids,
        pending_awards=storage.get_pending_awards(),
        achievement_game_ids=achievement_game_ids,
        game_id=game_id,
        user=user,
    )


def merge_start_session_unlock_ids(
    *,
    cached_unlock_ids: list[int],
    pending_awards: list[dict],
    achievement_game_ids: dict[int, int],
    game_id: int,
    user: str,
) -> list[int]:
    merged_ids: list[int] = []
    seen_ids: set[int] = set()

    for achievement_id in cached_unlock_ids:
        if achievement_id <= 0 or achievement_id in seen_ids:
            continue
        merged_ids.append(achievement_id)
        seen_ids.add(achievement_id)

    for award in pending_awards:
        if (
            award.get("status", PENDING_AWARD_STATUS_PENDING)
            != PENDING_AWARD_STATUS_PENDING
        ):
            continue

        achievement_id = int(award.get("achievementId", 0) or 0)
        if achievement_id <= 0 or achievement_id in seen_ids:
            continue

        if is_hardcore_request(
            award.get("queryString", ""), award.get("requestBody", "")
        ):
            continue

        if pending_award_user(award) != user:
            continue

        if achievement_game_ids.get(achievement_id) != game_id:
            continue

        merged_ids.append(achievement_id)
        seen_ids.add(achievement_id)

    return merged_ids


def build_achievement_game_ids(patch_entries: list[dict]) -> dict[int, int]:
    achievement_game_ids: dict[int, int] = {}
    for entry in patch_entries:
        game_id = cache_keys.parse_game_id_from_patch_key(entry.get("cacheKey", ""))
        if game_id is None:
            continue

        try:
            payload = json.loads(entry["responseBody"])
        except Exception:
            continue

        patch_data = payload.get("PatchData") or {}
        achievements = patch_data.get("Achievements", [])
        entries = (
            achievements.values() if isinstance(achievements, dict) else achievements
        )
        for achievement in entries:
            achievement_id = achievement.get("ID")
            if isinstance(achievement_id, int) and achievement_id > 0:
                achievement_game_ids.setdefault(achievement_id, game_id)
    return achievement_game_ids


def pending_award_user(award: dict) -> str | None:
    query_params = parse_form_params(
        award.get("queryString", "").split("?", 1)[1]
        if "?" in award.get("queryString", "")
        else ""
    )
    return query_params.get("u") or parse_form_params(award.get("requestBody", "")).get(
        "u"
    )


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
    hash_value: str,
    credentials: dict,
    user_agent: str,
    storage: Storage,
    config_data: dict,
) -> None:
    patch_body = refresh_game_patch(
        game_id,
        credentials,
        user_agent or FALLBACK_USER_AGENT,
        storage,
        config_data,
    )
    if patch_body is None:
        raise CacheGameError("patch failed")

    unlocks_body = cache_unlocks(
        game_id,
        credentials,
        user_agent or FALLBACK_USER_AGENT,
        config_data,
        storage,
    )
    cache_achievementsets(
        hash_value,
        credentials,
        user_agent or FALLBACK_USER_AGENT,
        config_data,
        storage,
    )
    cache_session(game_id, credentials, storage)
