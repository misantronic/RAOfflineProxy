import hashlib
import json
import logging
import tempfile
import urllib.request
import zipfile
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import urljoin

from . import cache_keys
from .auth import resolve_credentials
from .config import CONFIG_DIR, FALLBACK_USER_AGENT, ensure_config_dir, upstream_host
from .image_cache import (
    clear_all_cached_images,
    delete_cached_images_for_game,
    game_image_dir,
)
from .network import build_api_url, http_get
from .rom_cache import (
    build_achievement_game_ids,
    cache_game,
    merge_start_session_unlock_ids,
    merged_unlock_ids as merged_unlock_ids_for_user,
)
from .rom_hashing import hash_rom, hash_rom_candidates, supported_rom_extensions
from .storage import Storage
from .utils import proxy_user_agent

LOGGER = logging.getLogger("raofflineproxy")
SUPPORTED_ROM_EXTENSIONS = supported_rom_extensions()
SUPPORTED_ARCHIVE_EXTENSIONS = {".zip"}
MAX_CACHED_GAMES = 50


@dataclass
class CachedGameEntry:
    game_id: int
    title: str
    image_url: str | None = None


@dataclass
class AddRomResult:
    success: bool
    message: str
    game: CachedGameEntry | None = None


def list_cached_games(storage: Storage) -> list[CachedGameEntry]:
    games: dict[int, CachedGameEntry] = {}
    for entry in storage.get_all_cache_by_prefix(cache_keys.PREFIX_PATCH):
        game_id = cache_keys.parse_game_id_from_patch_key(entry["cacheKey"])
        if game_id is None:
            continue
        try:
            payload = json.loads(entry["responseBody"])
        except Exception:
            continue

        patch_data = payload.get("PatchData") or {}
        title = patch_data.get("Title") or f"Game {game_id}"
        image_path = patch_data.get("ImageIcon") or patch_data.get("ImageBoxArt")
        games[game_id] = CachedGameEntry(
            game_id=game_id,
            title=title,
            image_url=normalize_preview_url(image_path),
        )

    for entry in storage.get_all_cache_by_prefix(cache_keys.PREFIX_ACHIEVEMENTSETS):
        try:
            payload = json.loads(entry["responseBody"])
        except Exception:
            continue

        game_id = payload.get("GameId")
        if not isinstance(game_id, int) or game_id <= 0:
            continue

        title = payload.get("Title") or f"Game {game_id}"
        image_path = payload.get("ImageIcon") or payload.get("ImageIconUrl")
        if game_id not in games:
            games[game_id] = CachedGameEntry(
                game_id=game_id,
                title=title,
                image_url=normalize_preview_url(image_path),
            )

    return sorted(games.values(), key=lambda game: game.title.lower())


def list_browser_entries(current_dir: Path) -> list[Path]:
    directories: list[Path] = []
    files: list[Path] = []
    for entry in sorted(
        current_dir.iterdir(), key=lambda path: (not path.is_dir(), path.name.lower())
    ):
        if entry.name.startswith("."):
            continue
        if entry.is_dir():
            if directory_has_supported_roms(entry):
                directories.append(entry)
            continue
        if is_supported_browser_file(entry):
            files.append(entry)
    return directories + files


def directory_has_supported_roms(path: Path) -> bool:
    try:
        for entry in path.iterdir():
            if entry.name.startswith("."):
                continue
            if entry.is_file() and is_supported_browser_file(entry):
                return True
            if entry.is_dir() and directory_has_supported_roms(entry):
                return True
    except Exception:
        return False

    return False


def is_supported_browser_file(path: Path) -> bool:
    suffix = path.suffix.lower()
    if suffix in SUPPORTED_ROM_EXTENSIONS:
        return True
    if suffix in SUPPORTED_ARCHIVE_EXTENSIONS:
        return archive_has_supported_roms(path)
    return False


def archive_has_supported_roms(path: Path) -> bool:
    return bool(list_archive_rom_entries(path))


def list_archive_rom_entries(path: Path) -> list[zipfile.ZipInfo]:
    if path.suffix.lower() not in SUPPORTED_ARCHIVE_EXTENSIONS:
        return []

    try:
        with zipfile.ZipFile(path) as archive:
            return [
                info
                for info in archive.infolist()
                if not info.is_dir()
                and not Path(info.filename).name.startswith(".")
                and Path(info.filename).suffix.lower() in SUPPORTED_ROM_EXTENSIONS
            ]
    except Exception:
        return []


def hash_candidates_for_manual_cache(path: Path) -> list[str]:
    if path.suffix.lower() not in SUPPORTED_ARCHIVE_EXTENSIONS:
        return hash_rom_candidates(path)

    rom_entries = list_archive_rom_entries(path)
    if not rom_entries:
        raise ValueError("archive has no supported ROMs")
    if len(rom_entries) > 1:
        raise ValueError("archive contains multiple supported ROMs")

    rom_entry = rom_entries[0]
    with zipfile.ZipFile(path) as archive:
        with archive.open(rom_entry) as source:
            rom_bytes = source.read()

    suffix = Path(rom_entry.filename).suffix
    entry_name = Path(rom_entry.filename).stem
    with tempfile.TemporaryDirectory() as temp_dir:
        temp_name = entry_name if suffix else Path(rom_entry.filename).name
        temp_path = Path(temp_dir) / f"{temp_name}{suffix}"
        temp_path.write_bytes(rom_bytes)
        return hash_rom_candidates(temp_path)


def fetch_game_id(
    hash_value: str,
    credentials: dict,
    user_agent: str,
    config_data: dict,
    storage: Storage,
) -> int | None:
    url = build_api_url(
        upstream_host(config_data),
        "gameid",
        {
            "m": hash_value,
            "u": credentials["user"],
            "t": credentials["token"],
        },
    )
    response_body = http_get(url, proxy_user_agent(user_agent or FALLBACK_USER_AGENT))
    payload = json.loads(response_body)
    game_id = payload.get("GameID")
    if not isinstance(game_id, int) or game_id <= 0:
        return None

    storage.upsert_cache(cache_keys.game_id(hash_value), response_body)
    return int(game_id)


def add_rom_to_cache(path: Path, storage: Storage, config_data: dict) -> AddRomResult:
    user_agent = storage.load_user_agent(FALLBACK_USER_AGENT)
    credentials = resolve_credentials(storage, config_data, user_agent)
    if credentials is None:
        return AddRomResult(False, "RetroAchievements login required")

    try:
        hash_candidates = hash_candidates_for_manual_cache(path)
        if not hash_candidates:
            return AddRomResult(False, "Hash failed: unsupported or unreadable ROM")
        LOGGER.info(
            "Manual cache hash candidates path=%s candidates=%s",
            path,
            hash_candidates,
        )
    except Exception as exc:
        return AddRomResult(False, f"Hash failed: {exc}")

    game_id = None
    used_hash = None
    for hash_value in hash_candidates:
        try:
            candidate_game_id = fetch_game_id(
                hash_value, credentials, user_agent, config_data, storage
            )
        except Exception as exc:
            return AddRomResult(False, f"Game lookup failed: {exc}")

        if candidate_game_id is None:
            continue

        game_id = candidate_game_id
        used_hash = hash_value
        break

    if game_id is None:
        return AddRomResult(False, "No RetroAchievements match")

    cached_games = list_cached_games(storage)
    if len(cached_games) >= MAX_CACHED_GAMES and not any(
        game.game_id == game_id for game in cached_games
    ):
        return AddRomResult(
            False, f"Cache limit reached: {MAX_CACHED_GAMES} / {MAX_CACHED_GAMES}"
        )

    persist_game_id_aliases(storage, hash_candidates, used_hash, game_id)

    try:
        cache_game(
            game_id,
            credentials,
            proxy_user_agent(user_agent),
            storage,
            config_data,
        )
    except Exception as exc:
        return AddRomResult(False, f"Caching failed: {exc}")

    game = next(
        (entry for entry in list_cached_games(storage) if entry.game_id == game_id),
        None,
    )
    if game is None:
        return AddRomResult(False, "Caching failed: patch data was not stored")

    return AddRomResult(True, f"Cached {game.title}", game=game)


def persist_game_id_aliases(
    storage: Storage,
    hash_candidates: list[str],
    used_hash: str | None,
    game_id: int,
) -> None:
    response_body = json.dumps({"GameID": game_id}, separators=(",", ":"))
    for hash_value in hash_candidates:
        if hash_value == used_hash:
            continue
        storage.upsert_cache(cache_keys.game_id(hash_value), response_body)


def remove_cached_game(storage: Storage, game_id: int) -> None:
    storage.delete_cache_by_prefix(cache_keys.patch_prefix(game_id))
    storage.delete_cache_by_prefix(f"{cache_keys.PREFIX_UNLOCKS}{game_id}:")
    storage.delete_cache_by_prefix(f"{cache_keys.PREFIX_STARTSESSION}{game_id}:")
    remove_achievementsets_for_game(storage, game_id)
    remove_gameid_aliases_for_game(storage, game_id)
    delete_cached_images_for_game(game_id)


def remove_achievementsets_for_game(storage: Storage, game_id: int) -> None:
    for entry in storage.get_all_cache_by_prefix(cache_keys.PREFIX_ACHIEVEMENTSETS):
        try:
            payload = json.loads(entry["responseBody"])
        except Exception:
            continue

        if payload.get("GameId") != game_id:
            continue

        cache_key = entry.get("cacheKey")
        if isinstance(cache_key, str) and cache_key:
            storage.delete_cache(cache_key)


def remove_gameid_aliases_for_game(storage: Storage, game_id: int) -> None:
    for entry in storage.get_all_cache_by_prefix(cache_keys.PREFIX_GAMEID):
        try:
            payload = json.loads(entry["responseBody"])
        except Exception:
            continue

        if payload.get("GameID") != game_id:
            continue

        cache_key = entry.get("cacheKey")
        if isinstance(cache_key, str) and cache_key:
            storage.delete_cache(cache_key)


def cached_unlock_count(storage: Storage, game_id: int) -> int | None:
    unlock_ids = merged_unlock_ids(storage, game_id)
    if unlock_ids is None:
        return None
    return len(unlock_ids)


def cached_unlock_titles(storage: Storage, game_id: int) -> list[str]:
    unlock_ids = merged_unlock_ids(storage, game_id)
    if unlock_ids is None:
        return []

    patch_entry = next(
        (
            entry
            for entry in storage.get_all_cache_by_prefix(
                cache_keys.patch_prefix(game_id)
            )
        ),
        None,
    )
    if patch_entry is None:
        return []

    try:
        patch_payload = json.loads(patch_entry["responseBody"])
    except Exception:
        return []

    achievements = patch_payload.get("PatchData", {}).get("Achievements")
    title_by_id: dict[int, str] = {}
    if isinstance(achievements, list):
        for achievement in achievements:
            if not isinstance(achievement, dict):
                continue
            achievement_id = achievement.get("ID")
            title = achievement.get("Title")
            if isinstance(achievement_id, int) and isinstance(title, str):
                title_by_id[achievement_id] = title
    elif isinstance(achievements, dict):
        for achievement in achievements.values():
            if not isinstance(achievement, dict):
                continue
            achievement_id = achievement.get("ID")
            title = achievement.get("Title")
            if isinstance(achievement_id, int) and isinstance(title, str):
                title_by_id[achievement_id] = title

    titles = [
        title_by_id[achievement_id]
        for achievement_id in unlock_ids
        if achievement_id in title_by_id
    ]
    return titles


def merged_unlock_ids(storage: Storage, game_id: int) -> list[int] | None:
    cached_unlock_ids: list[int] | None = None
    unlock_user: str | None = None
    for entry in storage.get_all_cache_by_prefix(
        f"{cache_keys.PREFIX_UNLOCKS}{game_id}:"
    ):
        try:
            payload = json.loads(entry["responseBody"])
        except Exception:
            continue

        value = payload.get("UserUnlocks")
        if not isinstance(value, list):
            continue

        cached_unlock_ids = [item for item in value if isinstance(item, int)]
        unlock_user = parse_user_from_unlocks_key(entry.get("cacheKey", ""))
        break

    if cached_unlock_ids is None or unlock_user is None:
        return None
    return merged_unlock_ids_for_user(storage, game_id, unlock_user)


def parse_user_from_unlocks_key(cache_key: str) -> str | None:
    if not cache_key.startswith(cache_keys.PREFIX_UNLOCKS):
        return None

    parts = cache_key.split(":")
    if len(parts) != 4:
        return None

    user = parts[2].strip()
    return user or None


def cached_unlock_badge_path(storage: Storage, game_id: int, title: str) -> Path | None:
    patch_entry = next(
        (
            entry
            for entry in storage.get_all_cache_by_prefix(
                cache_keys.patch_prefix(game_id)
            )
        ),
        None,
    )
    if patch_entry is None:
        return None

    try:
        patch_payload = json.loads(patch_entry["responseBody"])
    except Exception:
        return None

    achievements = patch_payload.get("PatchData", {}).get("Achievements")
    entries = achievements.values() if isinstance(achievements, dict) else achievements
    if not isinstance(entries, list) and not hasattr(entries, "__iter__"):
        return None

    for achievement in entries:
        if not isinstance(achievement, dict):
            continue
        if achievement.get("Title") != title:
            continue
        badge_name = achievement.get("BadgeName")
        if not isinstance(badge_name, str) or not badge_name:
            return None
        badge_path = game_image_dir(game_id) / f"badge_{badge_name}.png"
        return badge_path if badge_path.exists() else None

    return None


def clear_cached_games(storage: Storage) -> None:
    storage.clear_cache()
    clear_all_cached_images()


def ensure_game_preview(
    game: CachedGameEntry,
    storage: Storage,
    config_data: dict,
) -> Path | None:
    if not game.image_url:
        return None

    preview_path = preview_cache_path(game.image_url)
    if preview_path.exists():
        return preview_path

    ensure_config_dir()
    preview_path.parent.mkdir(parents=True, exist_ok=True)
    user_agent = proxy_user_agent(storage.load_user_agent(FALLBACK_USER_AGENT))
    request = urllib.request.Request(
        game.image_url,
        headers={
            "User-Agent": user_agent,
            "Accept-Encoding": "identity",
        },
        method="GET",
    )

    with urllib.request.urlopen(request, timeout=10) as response:
        preview_path.write_bytes(response.read())

    return preview_path


def preview_cache_path(image_url: str) -> Path:
    digest = hashlib.sha1(image_url.encode("utf-8")).hexdigest()
    suffix = Path(image_url).suffix or ".img"
    return CONFIG_DIR / "game-previews" / f"{digest}{suffix}"


def normalize_preview_url(image_path: str | None) -> str | None:
    if not image_path:
        return None
    return urljoin(upstream_host({}) + "/", image_path.lstrip("/"))
