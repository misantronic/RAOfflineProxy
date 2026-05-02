import json
import logging
import shutil
from pathlib import Path

from .config import CONFIG_DIR, ensure_config_dir, upstream_host
from .utils import proxy_user_agent

LOGGER = logging.getLogger("raofflineproxy")
IMAGE_CACHE_DIR = CONFIG_DIR / "image_cache"
GAMES_DIR = IMAGE_CACHE_DIR / "games"
STATIC_DIR = IMAGE_CACHE_DIR / "static"


def game_image_dir(game_id: int) -> Path:
    return GAMES_DIR / str(game_id)


def static_asset_path(image_path: str) -> Path:
    clean_path = image_path.lstrip("/").split("?", 1)[0]
    return STATIC_DIR / clean_path


def original_image_path(game_id: int, image_path: str) -> Path:
    return game_image_dir(game_id) / image_path.split("/")[-1].split("?", 1)[0]


def legacy_game_icon_path(game_id: int, image_path: str) -> Path:
    clean = image_path.split("?", 1)[0]
    extension = Path(clean).suffix or ".png"
    return game_image_dir(game_id) / f"icon{extension}"


def fetch_binary_file(url: str, user_agent: str, target: Path) -> None:
    import urllib.request

    request = urllib.request.Request(
        url,
        headers={
            "User-Agent": user_agent,
            "Accept-Encoding": "identity",
        },
        method="GET",
    )
    with urllib.request.urlopen(request, timeout=10) as response:
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(response.read())


def mirror_file(source: Path, target: Path) -> None:
    if target.exists():
        return
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(source.read_bytes())


def trim_stale_images(game_id: int, keep_names: set[str]) -> None:
    directory = game_image_dir(game_id)
    if not directory.exists():
        return
    for file_path in directory.iterdir():
        if file_path.name not in keep_names:
            file_path.unlink(missing_ok=True)


def resolve_cached_static_asset(path: str) -> Path | None:
    asset = static_asset_path(path.split("?", 1)[0])
    return asset if asset.is_file() else None


def delete_cached_images_for_game(game_id: int) -> None:
    directory = game_image_dir(game_id)
    if directory.exists():
        shutil.rmtree(directory, ignore_errors=True)


def clear_all_cached_images() -> None:
    if IMAGE_CACHE_DIR.exists():
        shutil.rmtree(IMAGE_CACHE_DIR, ignore_errors=True)


def cache_patch_images(
    game_id: int,
    user_agent: str,
    patch_response_body: str,
    config_data: dict,
) -> None:
    try:
        patch_data = json.loads(patch_response_body).get("PatchData") or {}
        keep_names: set[str] = set()
        base = upstream_host(config_data).rstrip("/")
        ensure_config_dir()

        image_path = patch_data.get("ImageIcon") or patch_data.get("ImageBoxArt")
        if image_path:
            original_target = original_image_path(game_id, image_path)
            legacy_target = legacy_game_icon_path(game_id, image_path)
            static_target = static_asset_path(image_path)
            keep_names.update({original_target.name, legacy_target.name})
            if not original_target.exists():
                fetch_binary_file(f"{base}{image_path}", user_agent, original_target)
            mirror_file(original_target, static_target)
            mirror_file(original_target, legacy_target)

        for achievement in patch_data.get("Achievements") or []:
            badge_name = achievement.get("BadgeName")
            if not badge_name:
                continue
            badge_target = game_image_dir(game_id) / f"badge_{badge_name}.png"
            keep_names.add(badge_target.name)
            if not badge_target.exists():
                fetch_binary_file(
                    f"{base}/Badge/{badge_name}.png",
                    user_agent,
                    badge_target,
                )
            mirror_file(badge_target, static_asset_path(f"/Badge/{badge_name}.png"))

        trim_stale_images(game_id, keep_names)
    except Exception as exc:
        LOGGER.warning("Failed to cache patch images for gameId=%s: %s", game_id, exc)
