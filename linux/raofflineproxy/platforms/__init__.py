from __future__ import annotations

from . import allium, darkos, generic, knulli, muos, onion, rocknix, spruce, windows
from .base import Platform

# Detection order matters: the first platform that matches wins. Several firmwares leave
# folders behind on a shared card, so the more specific markers come first.
PLATFORMS: tuple[Platform, ...] = (
    windows.PLATFORM,
    allium.PLATFORM,
    spruce.PLATFORM,
    onion.PLATFORM,
    muos.PLATFORM,
    rocknix.PLATFORM,
    darkos.PLATFORM,
    knulli.PLATFORM,
)


def current() -> Platform:
    for platform in PLATFORMS:
        if platform.detect():
            return platform
    return generic.PLATFORM


def by_id(platform_id: str) -> Platform | None:
    for platform in PLATFORMS:
        if platform.id == platform_id:
            return platform
    return None


def running_on_shared_miyoo_stack() -> bool:
    """Onion, spruce and Allium share the same Miyoo Mini hardware, so this app ships one
    bundled stack for all three: the "Mini" SDL2 video driver, no fontconfig, and
    gpio-keys-polled raw evdev input."""
    return any(platform.detect() for platform in (onion.PLATFORM, spruce.PLATFORM, allium.PLATFORM))
