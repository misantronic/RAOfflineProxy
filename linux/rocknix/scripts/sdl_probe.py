"""Minimal SDL display probe used by sdl-doctor.sh.

Creates a window the same way the menu does and reports how far it got.
Kept dependency-free so a crash here is unambiguously an SDL/driver crash.
"""

from __future__ import annotations

import os
import sys
from pathlib import Path


def main() -> int:
    print(f"probe driver_env={os.environ.get('SDL_VIDEODRIVER', '<unset>')}", flush=True)

    import pygame

    print(f"probe pygame={pygame.version.ver} sdl={'.'.join(map(str, pygame.get_sdl_version()))}", flush=True)

    pygame.init()
    print("probe pygame.init ok", flush=True)

    pygame.display.init()
    print(f"probe display.init ok driver={pygame.display.get_driver()}", flush=True)
    print(
        f"probe num_displays={pygame.display.get_num_displays()} "
        f"desktop_sizes={pygame.display.get_desktop_sizes()}",
        flush=True,
    )

    mode = os.environ.get("PROBE_MODE", "fullscreen")
    if mode == "windowed":
        surface = pygame.display.set_mode((640, 480), 0)
    elif mode == "explicit":
        surface = pygame.display.set_mode((640, 480), pygame.FULLSCREEN)
    else:
        surface = pygame.display.set_mode((0, 0), pygame.FULLSCREEN)

    print(f"probe set_mode ok size={surface.get_size()}", flush=True)

    surface.fill((0, 128, 0))

    # The menu draws through SDL_ttf and SDL_image, which are separate bundled
    # libraries that also get interposed when the system SDL is preloaded.
    font = pygame.font.Font(None, 24)
    surface.blit(font.render("probe", True, (255, 255, 255)), (10, 10))
    print("probe font ok", flush=True)

    logo = Path(__file__).with_name("raofflineproxy") / "logo-320.png"
    if logo.exists():
        surface.blit(pygame.image.load(str(logo)), (10, 40))
        print("probe image ok", flush=True)

    pygame.display.flip()
    print("probe flip ok", flush=True)

    pygame.quit()
    print("probe SUCCESS", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
