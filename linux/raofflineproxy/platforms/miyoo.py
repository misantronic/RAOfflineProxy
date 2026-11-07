from __future__ import annotations

import os
from pathlib import Path

# Onion, spruce and Allium share this card layout, but spruce launches RetroArch with its own
# per-device --config file, so only Onion and Allium read this one.
RETROARCH_CFG_CANDIDATES = (Path("/mnt/SDCARD/RetroArch/.retroarch/retroarch.cfg"),)

ROMS_ROOT = Path("/mnt/SDCARD/Roms")


def running_on_mini_sdl_stack() -> bool:
    """The Renderer + streaming-texture display path exists for the vendored "Mini" SDL2 video
    driver, not for the firmware as a whole. Onion and Allium always select that driver;
    spruce only does on MiyooMini, because its aarch64 bundle ships a stock SDL2 that has no
    such driver and would fail at Renderer creation."""
    return os.environ.get("SDL_VIDEODRIVER") == "Mini"


DEFAULT_PANEL_SIZE = (640, 480)
SCREEN_RESOLUTION_FILE = Path("/tmp/screen_resolution")


def panel_size() -> tuple[int, int]:
    """The vendored "Mini" SDL2 driver ignores whatever size is passed to
    SDL_SetDisplayMode/pygame.display.set_mode: it self-detects the real
    framebuffer via its own `fbset` probe at init (640x480, or 750x560 on
    devices whose fbset mode reports "750", e.g. the Miyoo Mini Flip) and
    presents at that size
    regardless. So the window/texture here must match what the driver will
    actually use, or the two sizes fight each other. Onion's and Allium's own
    runtime.sh writes the same underlying panel probe result to
    /tmp/screen_resolution, so read that rather than re-deriving it (and
    rather than assuming 640x480, which is only one of Onion's panel
    variants).
    """
    try:
        raw = SCREEN_RESOLUTION_FILE.read_text(encoding="utf-8").strip()
        width_str, height_str = raw.split("x", 1)
        width, height = int(width_str), int(height_str)
        if width > 0 and height > 0:
            return (width, height)
    except (OSError, ValueError):
        pass
    return DEFAULT_PANEL_SIZE


def open_mini_display(pygame, log):
    """Onion's SDL2 video driver only presents through the SDL_Renderer +
    streaming-texture path; SDL_UpdateWindowSurface (what pygame.display.flip
    normally uses) silently no-ops there. Route flip() through a renderer
    instead, keeping the surface pygame draws onto in RGB565 (the only pixel
    format that doesn't crash the renderer's texture upload on this driver).
    """
    from pygame._sdl2.video import Renderer, Texture, Window

    panel_size = panel_size()
    pygame.display.set_mode(panel_size, 0)
    window = Window.from_display_module()
    renderer = Renderer(window, accelerated=-1, vsync=False)
    draw_surface = pygame.Surface(panel_size, depth=16)

    try:
        texture = Texture(renderer, panel_size, streaming=True)
        texture_surface = draw_surface
        log(f"mini display texture native size={panel_size[0]}x{panel_size[1]}")
    except RuntimeError as exc:
        # The vendored "Mini" SDL2 driver's swiftshader renderer caps
        # streaming textures at 640x480 regardless of the actual panel
        # (e.g. the Miyoo Mini Flip's 750x560 screen exceeds it). Present
        # through a capped-size texture and let SDL_RenderCopy scale it
        # back up to the real panel size. The renderer is sized from the
        # live framebuffer at init, so this also trips when the panel is
        # 560p but Onion launched the app in MainUI's 640x480 mode (i.e.
        # the App/RAOfflineProxy/full_resolution marker is missing).
        log(
            f"mini display texture {panel_size[0]}x{panel_size[1]} rejected "
            f"({exc}), scaling through "
            f"{DEFAULT_PANEL_SIZE[0]}x{DEFAULT_PANEL_SIZE[1]}"
        )
        texture_surface = pygame.Surface(DEFAULT_PANEL_SIZE, depth=16)
        texture = Texture(renderer, DEFAULT_PANEL_SIZE, streaming=True)

    def _present() -> None:
        if texture_surface is draw_surface:
            texture.update(draw_surface)
        else:
            pygame.transform.scale(draw_surface, texture_surface.get_size(), texture_surface)
            texture.update(texture_surface)
        renderer.clear()
        texture.draw(dstrect=(0, 0, panel_size[0], panel_size[1]))
        renderer.present()

    pygame.display.flip = _present
    return draw_surface
