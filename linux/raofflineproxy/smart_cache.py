import json
import time
from dataclasses import dataclass
from pathlib import Path

from .config import detect_ppsspp_ini
from .platform import read_retroarch_cfg_values, resolve_retroarch_cfg
from .rom_browser import (
    MAX_CACHED_GAMES,
    add_rom_to_cache,
    list_browser_files_fast,
    list_cached_games,
    load_cached_rom_paths,
    normalize_cached_rom_path,
)
from .storage import Storage

SMART_CACHE_LIMIT = MAX_CACHED_GAMES
SMART_CACHE_DELAY_SECONDS = 0.5
MUOS_HISTORY_DIR = Path("/run/muos/storage/info/history")


@dataclass
class SmartCacheStatus:
    found_history: bool
    total_candidates: int
    reason: str | None = None
    history_path: str | None = None


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
        return SmartCacheStatus(
            found_history=False,
            total_candidates=0,
            reason="offline" if not is_online else "missing_credentials",
        )

    history_source = _find_history_source(config_data)
    if history_source is None:
        return SmartCacheStatus(
            found_history=False,
            total_candidates=0,
            reason="content_history_missing",
        )

    cached_rom_paths = load_cached_rom_paths(storage)
    all_paths = load_content_history_paths(config_data)
    paths = [
        path
        for path in all_paths
        if normalize_cached_rom_path(path) not in cached_rom_paths
    ]
    total_candidates = min(len(paths), SMART_CACHE_LIMIT)

    if total_candidates == 0:
        return SmartCacheStatus(
            found_history=False,
            total_candidates=0,
            reason=(
                "all_history_entries_cached"
                if all_paths and len(paths) == 0
                else "no_valid_history_entries"
            ),
            history_path=str(history_source),
        )

    return SmartCacheStatus(
        found_history=total_candidates > 0,
        total_candidates=total_candidates,
        history_path=str(history_source),
    )


def run_smart_cache(
    storage: Storage,
    config_data: dict,
    limit: int = SMART_CACHE_LIMIT,
    should_abort=None,
    on_progress=None,
) -> SmartCacheResult:
    cached_rom_paths = load_cached_rom_paths(storage)
    return run_cache_paths(
        storage,
        config_data,
        [
            path
            for path in load_content_history_paths(config_data)
            if normalize_cached_rom_path(path) not in cached_rom_paths
        ],
        limit=limit,
        should_abort=should_abort,
        on_progress=on_progress,
    )


def run_folder_cache(
    storage: Storage,
    config_data: dict,
    current_dir: Path,
    paths: list[Path] | None = None,
    should_abort=None,
    on_progress=None,
) -> SmartCacheResult:
    return run_cache_paths(
        storage,
        config_data,
        list_browser_files_fast(current_dir) if paths is None else paths,
        limit=MAX_CACHED_GAMES,
        should_abort=should_abort,
        on_progress=on_progress,
    )


def run_cache_paths(
    storage: Storage,
    config_data: dict,
    paths: list[Path],
    *,
    limit: int,
    should_abort=None,
    on_progress=None,
) -> SmartCacheResult:
    total = min(len(paths), limit, MAX_CACHED_GAMES)
    cached = 0
    scanned = 0

    for path in paths[:total]:
        if should_abort is not None and should_abort():
            break

        if len(list_cached_games(storage)) >= MAX_CACHED_GAMES or cached >= limit:
            break

        scanned += 1

        if on_progress is not None:
            on_progress(
                SmartCacheProgress(
                    scanned=scanned,
                    total=total,
                    cached=cached,
                    current_label=path.name,
                )
            )

        result = add_rom_to_cache(path, storage, config_data)
        if result.success:
            cached += 1

        if should_abort is not None and should_abort():
            break

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
    if MUOS_HISTORY_DIR.exists():
        paths = _load_muos_history_paths()
    else:
        paths = _load_retroarch_history_paths(config_data)

    ppsspp_paths = _load_ppsspp_recent_paths(config_data)
    if not ppsspp_paths:
        return paths

    seen = {str(path) for path in paths}
    for path in ppsspp_paths:
        normalized = str(path)
        if normalized in seen:
            continue
        seen.add(normalized)
        paths.append(path)

    return paths

def _load_retroarch_history_paths(config_data: dict) -> list[Path]:
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


def _find_history_source(config_data: dict) -> Path | None:
    """Returns the muOS history dir, content_history.lpl, or ppsspp.ini, whichever is available."""
    if MUOS_HISTORY_DIR.exists():
        return MUOS_HISTORY_DIR

    retroarch_source = find_content_history_lpl(config_data)
    if retroarch_source is not None:
        return retroarch_source

    ppsspp_ini = detect_ppsspp_ini(config_data)
    if ppsspp_ini is not None and Path(ppsspp_ini).exists():
        return Path(ppsspp_ini)

    return None


def _load_ppsspp_recent_paths(config_data: dict) -> list[Path]:
    ini_path = detect_ppsspp_ini(config_data)
    if ini_path is None:
        return []

    target = Path(ini_path)
    if not target.exists():
        return []

    try:
        content = target.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return []

    return parse_ppsspp_recent_paths(content)


def parse_ppsspp_recent_paths(content: str) -> list[Path]:
    """Read ROM paths from the [Recent] section of ppsspp.ini.

    Entries are keyed FileName0, FileName1, ... with 0 being most recent;
    sort by that index the same way PPSSPP's own recent list is ordered.
    """
    in_recent = False
    seen: set[str] = set()
    candidates: list[tuple[int, Path]] = []

    for line in content.splitlines():
        stripped = line.strip()
        if stripped.startswith("[") and stripped.endswith("]"):
            in_recent = stripped == "[Recent]"
            continue
        if not in_recent:
            continue

        separator = stripped.find("=")
        if separator == -1:
            continue
        key = stripped[:separator].strip()
        if not key.startswith("FileName"):
            continue
        index_text = key[len("FileName") :]
        if not index_text.isdigit():
            continue

        raw_path = stripped[separator + 1 :].strip()
        if not raw_path:
            continue

        path = Path(raw_path).expanduser()
        normalized = str(path)
        if normalized in seen or not path.exists() or not path.is_file():
            continue
        seen.add(normalized)
        candidates.append((int(index_text), path))

    candidates.sort(key=lambda item: item[0])
    return [path for _, path in candidates]


def _load_muos_history_paths() -> list[Path]:
    """Read ROM paths from muOS per-game history cfg files in MUOS_HISTORY_DIR.

    Each .cfg file has three lines:
        line 1 — absolute path to the ROM file
        line 2 — platform/system name (e.g. "gba")
        line 3 — display name (no trailing newline)
    """
    unique_paths: list[Path] = []
    seen: set[str] = set()
    try:
        entries = sorted(MUOS_HISTORY_DIR.iterdir())
    except OSError:
        return []
    for cfg_file in entries:
        if not cfg_file.name.endswith(".cfg"):
            continue
        try:
            lines = cfg_file.read_text(encoding="utf-8", errors="replace").splitlines()
        except OSError:
            continue
        if not lines:
            continue
        path = Path(lines[0].strip())
        normalized = str(path)
        if normalized in seen or not path.exists() or not path.is_file():
            continue
        seen.add(normalized)
        unique_paths.append(path)
    return unique_paths


def find_content_history_lpl(config_data: dict) -> Path | None:
    cfg_path = Path(resolve_retroarch_cfg(config_data))
    cfg_values = read_retroarch_cfg_values(cfg_path)

    candidates: list[Path] = []

    # Honour the explicit path RetroArch writes in its own config (e.g. muOS sets
    # content_history_path = "/opt/muos/share/emulator/retroarch/content_history.lpl").
    # RetroArch may store it relative to its home with a leading ~ (ROCKNIX uses
    # "~/playlists/builtin/content_history.lpl"), so expand it.
    explicit = cfg_values.get("content_history_path", "").strip().strip('"')
    if explicit and explicit != "default":
        candidates.append(Path(explicit).expanduser())

    # Derive from the playlist directory when set (also possibly ~-relative).
    playlist_dir = cfg_values.get("playlist_directory", "").strip().strip('"')
    if playlist_dir and playlist_dir != "default":
        playlist_base = Path(playlist_dir).expanduser()
        candidates.append(playlist_base / "content_history.lpl")
        candidates.append(playlist_base / "builtin" / "content_history.lpl")

    candidates += [
        cfg_path.parent / "content_history.lpl",
        cfg_path.parent / "playlists" / "content_history.lpl",
        cfg_path.parent / "playlists" / "builtin" / "content_history.lpl",
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
