from __future__ import annotations

import json
import logging
import os

from . import cache_keys
from .config import CONFIG_DIR

CACHED_IDS_FILE = CONFIG_DIR / "cached_game_ids.txt"
LOGGER = logging.getLogger("raofflineproxy")

_RELEVANT_PREFIXES = (cache_keys.PREFIX_PATCH, cache_keys.PREFIX_ACHIEVEMENTSETS)


def key_affects_cached_game_ids(cache_key: str | None) -> bool:
    if cache_key is None:
        return True
    return cache_key.startswith(_RELEVANT_PREFIXES)


def collect_cached_game_ids(storage) -> set[int]:
    ids: set[int] = set()

    for key in storage.cache_keys_by_prefix(cache_keys.PREFIX_PATCH):
        game_id = cache_keys.parse_game_id_from_patch_key(key)
        if game_id is not None and game_id > 0:
            ids.add(game_id)

    for entry in storage.get_all_cache_by_prefix(cache_keys.PREFIX_ACHIEVEMENTSETS):
        try:
            payload = json.loads(entry["responseBody"])
        except Exception:
            continue
        game_id = payload.get("GameId")
        if isinstance(game_id, int) and game_id > 0:
            ids.add(game_id)

    return ids


def export_cached_game_ids(storage) -> None:
    try:
        _write_ids(collect_cached_game_ids(storage))
    except Exception:
        LOGGER.exception("Failed to export cached game ids")


def add_cached_game_id(storage, cache_key: str, response_body: str) -> None:
    """Adds the game a cache write belongs to without rescanning the library: every newly
    cached game writes several entries, and a full rescan reads every cached game each time.
    Removals still rebuild the whole list, which also repairs anything this missed."""
    try:
        game_id = _game_id_for_entry(cache_key, response_body)
        if game_id is None:
            return
        try:
            known = {int(line) for line in CACHED_IDS_FILE.read_text().split() if line.isdigit()}
        except OSError:
            export_cached_game_ids(storage)
            return
        if game_id not in known:
            _write_ids(known | {game_id})
    except Exception:
        LOGGER.exception("Failed to export cached game ids")


def _game_id_for_entry(cache_key: str, response_body: str) -> int | None:
    if cache_key.startswith(cache_keys.PREFIX_PATCH):
        return cache_keys.parse_game_id_from_patch_key(cache_key)
    try:
        game_id = json.loads(response_body).get("GameId")
    except Exception:
        return None
    return game_id if isinstance(game_id, int) and game_id > 0 else None


def _write_ids(ids: set[int]) -> None:
    content = "".join(f"{game_id}\n" for game_id in sorted(ids))
    try:
        if CACHED_IDS_FILE.read_text() == content:
            return
    except OSError:
        pass

    CONFIG_DIR.mkdir(parents=True, exist_ok=True)
    tmp_path = CACHED_IDS_FILE.with_name(CACHED_IDS_FILE.name + ".tmp")
    tmp_path.write_text(content)
    os.replace(tmp_path, CACHED_IDS_FILE)
