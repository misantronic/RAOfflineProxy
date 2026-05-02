import hashlib
import json
import logging
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import urljoin

from . import cache_keys
from .auth import resolve_credentials
from .config import CONFIG_DIR, FALLBACK_USER_AGENT, ensure_config_dir, upstream_host
from .image_cache import clear_all_cached_images, delete_cached_images_for_game
from .network import build_api_url, http_get
from .rom_cache import cache_game
from .rom_hashing import hash_rom, hash_rom_candidates, supported_rom_extensions
from .storage import Storage
from .utils import proxy_user_agent

LOGGER = logging.getLogger("raofflineproxy")
SUPPORTED_ROM_EXTENSIONS = supported_rom_extensions()
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
        if entry.suffix.lower() in SUPPORTED_ROM_EXTENSIONS:
            files.append(entry)
    return directories + files


def directory_has_supported_roms(path: Path) -> bool:
    try:
        for entry in path.iterdir():
            if entry.name.startswith("."):
                continue
            if entry.is_file() and entry.suffix.lower() in SUPPORTED_ROM_EXTENSIONS:
                return True
            if entry.is_dir() and directory_has_supported_roms(entry):
                return True
    except Exception:
        return False

    return False


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
        hash_candidates = hash_rom_candidates(path)
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
    delete_cached_images_for_game(game_id)


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
