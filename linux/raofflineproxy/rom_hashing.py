"""RetroAchievements ROM hashing for the Linux proxy.

This is a thin ctypes binding over ``libraproxy_rchash`` — the shared library
built from rcheevos' ``rc_hash`` plus the vendored libchdr-backed CHD reader
(see ``third_party/rcheevos_glue``). All the per-format header-stripping,
sector cooking and disc parsing now lives in that one C library, shared with
the Android build, instead of being re-implemented here in Python.

``raproxy_hash_file(path, out_buffer, max_hashes)`` fills ``out_buffer`` with up
to ``max_hashes`` NUL-terminated 32-char hex hashes (33 bytes each) in rc_hash
iterator order and returns the count. We surface those as ordered candidates;
the caller tries each against the game-id lookup.

Note: ``.zip`` archives containing a single console ROM are extracted by the
caller (``rom_browser``) before hashing — rcheevos' own zip path is for
arcade/MAME images, not zipped cartridges. ``.cue`` and ``.m3u`` are resolved
natively by rc_hash from their path.
"""

import ctypes
import ctypes.util
import logging
import os
from dataclasses import dataclass
from pathlib import Path

LOGGER = logging.getLogger("raofflineproxy")

_HASH_STRIDE = 33  # 32 hex chars + NUL, matching rc_hash's char[33]
_MAX_CANDIDATES = 8

_LIBRCHASH: ctypes.CDLL | None = None
_LIBRCHASH_ERROR: str | None = None


@dataclass
class RomHashResult:
    candidates: list[str]
    error: str | None = None


def _candidate_library_paths() -> list[str | None]:
    names = ("libraproxy_rchash.so", "libraproxy_rchash.dylib")
    paths: list[str | None] = []

    override = os.environ.get("RAOFFLINEPROXY_RCHASH_LIB")
    if override:
        paths.append(override)

    paths.append(ctypes.util.find_library("raproxy_rchash"))

    # Bundled next to this module (how the distro packages ship it).
    here = Path(__file__).resolve().parent
    for name in names:
        paths.append(str(here / name))
        paths.append(str(here / "lib" / name))

    # Standard + device install locations (mirrors load paths used for libchdr).
    bases = (
        "/usr/lib",
        "/usr/local/lib",
        "/lib",
        "/userdata/system/lib",
        "/userdata/system/raofflineproxy/lib",
        "/mnt/SDCARD/App/RAOfflineProxy/lib",
        "/run/muos/storage/application/RAOfflineProxy/lib",
    )
    for base in bases:
        for name in names:
            paths.append(f"{base}/{name}")

    return paths


def load_rchash() -> ctypes.CDLL | None:
    global _LIBRCHASH, _LIBRCHASH_ERROR
    if _LIBRCHASH is not None:
        return _LIBRCHASH
    if _LIBRCHASH_ERROR is not None:
        return None

    for candidate in _candidate_library_paths():
        if not candidate:
            continue
        if "/" in candidate and not os.path.isfile(candidate):
            continue
        try:
            library = ctypes.CDLL(candidate)
            library.raproxy_hash_file.argtypes = [
                ctypes.c_char_p,
                ctypes.c_char_p,
                ctypes.c_int,
            ]
            library.raproxy_hash_file.restype = ctypes.c_int
            _LIBRCHASH = library
            LOGGER.debug("Loaded libraproxy_rchash from %s", candidate)
            return library
        except OSError as exc:
            if _LIBRCHASH_ERROR is None:
                _LIBRCHASH_ERROR = f"libraproxy_rchash load failed: {exc}"
        except AttributeError as exc:
            if _LIBRCHASH_ERROR is None:
                _LIBRCHASH_ERROR = f"libraproxy_rchash API mismatch: {exc}"

    if _LIBRCHASH_ERROR is None:
        _LIBRCHASH_ERROR = "libraproxy_rchash shared library not found"
    return None


def _generate_candidates(path: Path) -> list[str]:
    library = load_rchash()
    if library is None:
        return []

    buffer = ctypes.create_string_buffer(_MAX_CANDIDATES * _HASH_STRIDE)
    count = library.raproxy_hash_file(
        str(path).encode("utf-8"), buffer, _MAX_CANDIDATES
    )

    candidates: list[str] = []
    for index in range(max(count, 0)):
        chunk = buffer.raw[index * _HASH_STRIDE : (index + 1) * _HASH_STRIDE]
        value = chunk.split(b"\x00", 1)[0].decode("ascii", "ignore").strip().lower()
        if value and value not in candidates:
            candidates.append(value)
    return candidates


def hash_rom_candidates_result(path: Path) -> RomHashResult:
    path = Path(path)
    candidates = _generate_candidates(path)
    if candidates:
        return RomHashResult(candidates)

    if _LIBRCHASH_ERROR is not None:
        return RomHashResult([], _LIBRCHASH_ERROR)
    return RomHashResult([], f"Could not hash {path.name}")


def hash_rom_candidates(path: Path) -> list[str]:
    return hash_rom_candidates_result(path).candidates


def hash_rom(path: Path) -> str | None:
    candidates = hash_rom_candidates(path)
    return candidates[0] if candidates else None


def supported_rom_extensions() -> set[str]:
    return {
        ".gb",
        ".gbc",
        ".gba",
        ".nes",
        ".fds",
        ".smc",
        ".sfc",
        ".fig",
        ".swc",
        ".pce",
        ".sgx",
        ".a78",
        ".lnx",
        ".cart",
        ".z64",
        ".n64",
        ".v64",
        ".nds",
        ".iso",
        ".bin",
        ".chd",
        ".pbp",
        ".cue",
        ".m3u",
    }
