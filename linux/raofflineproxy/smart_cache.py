import json
import time
from dataclasses import dataclass
from pathlib import Path

from .platform import resolve_retroarch_cfg
from .rom_browser import (
    MAX_CACHED_GAMES,
    add_rom_to_cache,
    list_browser_files_fast,
    list_cached_games,
)
from .storage import Storage

SMART_CACHE_LIMIT = 25
SMART_CACHE_DELAY_SECONDS = 0.5


@dataclass
class SmartCacheStatus:
    found_history: bool
    total_candidates: int


@dataclass
class SmartCacheProgress:
    scanned: int
    total: int
    cached: int
    current_label: str


@dataclass
class SmartCacheResult:
    scanned: int
    total: int
    cached: int
    skipped: int
    limit_reached: bool


def should_offer_smart_cache(
    storage: Storage,
    config_data: dict,
    *,
    is_online: bool,
    has_credentials: bool,
) -> SmartCacheStatus:
    if not is_online or not has_credentials:
        return SmartCacheStatus(found_history=False, total_candidates=0)

    if list_cached_games(storage):
        return SmartCacheStatus(found_history=False, total_candidates=0)

    paths = load_content_history_paths(config_data)
    return SmartCacheStatus(found_history=bool(paths), total_candidates=len(paths))


def run_smart_cache(
    storage: Storage,
    config_data: dict,
    limit: int = SMART_CACHE_LIMIT,
    on_progress=None,
) -> SmartCacheResult:
    return run_cache_paths(
        storage,
        config_data,
        load_content_history_paths(config_data),
        limit=limit,
        on_progress=on_progress,
    )


def run_folder_cache(
    storage: Storage,
    config_data: dict,
    current_dir: Path,
    on_progress=None,
) -> SmartCacheResult:
    return run_cache_paths(
        storage,
        config_data,
        list_browser_files_fast(current_dir),
        limit=MAX_CACHED_GAMES,
        on_progress=on_progress,
    )


def run_cache_paths(
    storage: Storage,
    config_data: dict,
    paths: list[Path],
    *,
    limit: int,
    on_progress=None,
) -> SmartCacheResult:
    total = len(paths)
    cached = 0
    scanned = 0

    for path in paths:
        if len(list_cached_games(storage)) >= MAX_CACHED_GAMES or cached >= limit:
            break

        scanned += 1
        result = add_rom_to_cache(path, storage, config_data)
        if result.success:
            cached += 1

        if on_progress is not None:
            on_progress(
                SmartCacheProgress(
                    scanned=scanned,
                    total=total,
                    cached=cached,
                    current_label=path.name,
                )
            )

        if scanned < total:
            time.sleep(SMART_CACHE_DELAY_SECONDS)

    skipped = max(0, scanned - cached)
    limit_reached = (
        cached >= limit or len(list_cached_games(storage)) >= MAX_CACHED_GAMES
    )
    return SmartCacheResult(
        scanned=scanned,
        total=total,
        cached=cached,
        skipped=skipped,
        limit_reached=limit_reached,
    )


def load_content_history_paths(config_data: dict) -> list[Path]:
    history_path = find_content_history_lpl(config_data)
    if history_path is None or not history_path.exists():
        return []

    try:
        payload = json.loads(history_path.read_text(encoding="utf-8", errors="replace"))
    except Exception:
        return []

    items = payload.get("items")
    if not isinstance(items, list):
        return []

    unique_paths: list[Path] = []
    seen: set[str] = set()
    for item in items:
        if not isinstance(item, dict):
            continue
        raw_path = item.get("path")
        if not isinstance(raw_path, str) or not raw_path.strip():
            continue

        path = Path(raw_path).expanduser()
        normalized = str(path)
        if normalized in seen or not path.exists() or not path.is_file():
            continue
        seen.add(normalized)
        unique_paths.append(path)

    return unique_paths


def find_content_history_lpl(config_data: dict) -> Path | None:
    cfg_path = Path(resolve_retroarch_cfg(config_data))
    candidates = [
        cfg_path.parent / "content_history.lpl",
        cfg_path.parent / "playlists" / "content_history.lpl",
        cfg_path.parent.parent / "playlists" / "content_history.lpl",
        cfg_path.parent.parent.parent
        / "Saves"
        / "CurrentProfile"
        / "lists"
        / "content_history.lpl",
    ]
    for candidate in candidates:
        if candidate.exists():
            return candidate
    return None
