import ctypes
import ctypes.util
import hashlib
import logging
import math
import os
from dataclasses import dataclass
from pathlib import Path

ISO_SECTOR = 2048
RAW_2352_SECTOR = 2352
MODE2_2336_SECTOR = 2336
PVD_SECTOR = 16
PVD_SCAN_WINDOW = 32
PVD_LOGICAL_BLOCK_SIZE_OFFSET = 128
PVD_MAGIC = b"CD001"
NDS_HEADER_SIZE = 512
NDS_ICON_BLOCK_SIZE = 0xA00
NDS_HASHED_HEADER_SIZE = 0x160
NDS_MAX_CODE_SIZE = 16 * 1024 * 1024
NDS_SUPERCARD_HEADER_SIZE = 512
PSX_MAX_HASH_BYTES = 64 * 1024 * 1024
PSX_EXE_HEADER_SIZE = 2048
PSX_EXE_BODY_SIZE_OFFSET = 0x1C
PSX_EXE_MAGIC = b"PS-X EXE"
PSP_MAX_HASH_BYTES = 64 * 1024 * 1024
PSP_PARAM_PATH = "PSP_GAME\\PARAM.SFO"
PSP_EBOOT_PATH = "PSP_GAME\\SYSDIR\\EBOOT.BIN"
N64_HASH_LIMIT_BYTES = 64 * 1024 * 1024
N64_BUFFER_SIZE = 65536
CHD_OPEN_READ = 1
CHDERR_NONE = 0
CDROM_TRACK_METADATA_TAG = 0x43485452
CDROM_TRACK_METADATA2_TAG = 0x43485432
CD_FRAME_SIZE = 2448

LOGGER = logging.getLogger("raofflineproxy")
_LIBCHDR: ctypes.CDLL | None = None
_LIBCHDR_ERROR: str | None = None


class ChdHeader(ctypes.Structure):
    _fields_ = [
        ("length", ctypes.c_uint32),
        ("version", ctypes.c_uint32),
        ("flags", ctypes.c_uint32),
        ("compression", ctypes.c_uint32 * 4),
        ("hunkbytes", ctypes.c_uint32),
        ("totalhunks", ctypes.c_uint32),
        ("logicalbytes", ctypes.c_uint64),
        ("metaoffset", ctypes.c_uint64),
        ("mapoffset", ctypes.c_uint64),
        ("md5", ctypes.c_uint8 * 16),
        ("parentmd5", ctypes.c_uint8 * 16),
        ("sha1", ctypes.c_uint8 * 20),
        ("rawsha1", ctypes.c_uint8 * 20),
        ("parentsha1", ctypes.c_uint8 * 20),
        ("unitbytes", ctypes.c_uint32),
        ("unitcount", ctypes.c_uint64),
        ("hunkcount", ctypes.c_uint32),
        ("mapentrybytes", ctypes.c_uint32),
        ("rawmap", ctypes.c_void_p),
        ("obsolete_cylinders", ctypes.c_uint32),
        ("obsolete_sectors", ctypes.c_uint32),
        ("obsolete_heads", ctypes.c_uint32),
        ("obsolete_hunksize", ctypes.c_uint32),
    ]


@dataclass
class RomHashInput:
    file_name: str
    file_size: int
    path: Path


@dataclass
class SectorLayout:
    raw_sector_size: int
    data_offset: int
    sector_bias: int


@dataclass
class IsoFileRecord:
    sector: int
    size: int
    name: str
    is_directory: bool


@dataclass
class PsxExecutable:
    path: str
    sector: int
    size: int


@dataclass
class RomHashResult:
    candidates: list[str]
    error: str | None = None


@dataclass
class ChdOpenResult:
    handle: object | None
    error: str | None = None


@dataclass
class ChdTrackInfo:
    number: int
    type: str
    frames: int
    pregap: int = 0
    postgap: int = 0


class DirectChdReader:
    def __init__(self, disc_handle) -> None:
        self._disc_handle = disc_handle
        self._position = 0

    def __enter__(self):
        return self

    def __exit__(self, _exc_type, _exc, _traceback) -> None:
        self.close()

    def close(self) -> None:
        if self._disc_handle is not None:
            self._disc_handle.close()
            self._disc_handle = None

    def seek(self, offset: int, whence: int = 0) -> int:
        if self._disc_handle is None:
            raise ValueError("reader is closed")

        if whence == 0:
            target = offset
        elif whence == 1:
            target = self._position + offset
        elif whence == 2:
            target = self._disc_handle.logical_length + offset
        else:
            raise ValueError(f"invalid whence: {whence}")

        self._position = max(0, target)
        return self._position

    def read(self, length: int = -1) -> bytes:
        if self._disc_handle is None:
            raise ValueError("reader is closed")

        remaining = max(0, self._disc_handle.logical_length - self._position)
        if length < 0 or length > remaining:
            length = remaining

        data = self._disc_handle.read(self._position, length)
        self._position += len(data)
        return data


class LibChdrDiscHandle:
    def __init__(
        self,
        library: ctypes.CDLL,
        chd_handle: ctypes.c_void_p,
        hunk_bytes: int,
        first_track_start_frame: int,
        first_track_frames: int,
    ) -> None:
        self._library = library
        self._chd_handle = chd_handle
        self._hunk_bytes = hunk_bytes
        self._first_track_start_frame = first_track_start_frame
        self._first_track_frames = first_track_frames
        self._cached_hunk = -1
        self._hunk_buffer = ctypes.create_string_buffer(hunk_bytes)
        self.logical_length = first_track_frames * RAW_2352_SECTOR

    def close(self) -> None:
        if self._chd_handle:
            self._library.chd_close(self._chd_handle)
            self._chd_handle = ctypes.c_void_p()

    def read(self, offset: int, requested_length: int) -> bytes:
        if offset >= self.logical_length or requested_length <= 0:
            return b""

        target_length = min(requested_length, self.logical_length - offset)
        output = bytearray()
        while len(output) < target_length:
            projected_offset = offset + len(output)
            absolute_frame = self._first_track_start_frame + (
                projected_offset // RAW_2352_SECTOR
            )
            sector_offset = projected_offset % RAW_2352_SECTOR
            absolute_offset = absolute_frame * CD_FRAME_SIZE
            hunk = absolute_offset // self._hunk_bytes
            hunk_offset = (absolute_offset % self._hunk_bytes) + sector_offset

            if self._cached_hunk != hunk:
                result = self._library.chd_read(
                    self._chd_handle, ctypes.c_uint32(hunk), self._hunk_buffer
                )
                if result != CHDERR_NONE:
                    raise OSError(chd_error_string(self._library, result))
                self._cached_hunk = hunk

            bytes_until_sector_end = RAW_2352_SECTOR - sector_offset
            bytes_until_hunk_end = self._hunk_bytes - hunk_offset
            chunk = min(
                target_length - len(output),
                min(bytes_until_sector_end, bytes_until_hunk_end),
            )
            output.extend(self._hunk_buffer.raw[hunk_offset : hunk_offset + chunk])

        return bytes(output)


class RomHashStrategy:
    def matches(self, file_name: str) -> bool:
        raise NotImplementedError

    def hash(self, rom_input: RomHashInput) -> str | None:
        raise NotImplementedError


class GenericMd5RomHashStrategy(RomHashStrategy):
    def matches(self, file_name: str) -> bool:
        return False

    def hash(self, rom_input: RomHashInput) -> str | None:
        return md5_hash_with_header_rule(
            rom_input, 0, lambda _header, _bytes_read, _file_size: 0
        )


class GameBoyRomHashStrategy(RomHashStrategy):
    def matches(self, file_name: str) -> bool:
        return has_extension(file_name, "gb")

    def hash(self, rom_input: RomHashInput) -> str | None:
        return GenericMd5RomHashStrategy().hash(rom_input)


class GameBoyColorRomHashStrategy(RomHashStrategy):
    def matches(self, file_name: str) -> bool:
        return has_extension(file_name, "gbc")

    def hash(self, rom_input: RomHashInput) -> str | None:
        return GenericMd5RomHashStrategy().hash(rom_input)


class GameBoyAdvanceRomHashStrategy(RomHashStrategy):
    def matches(self, file_name: str) -> bool:
        return has_extension(file_name, "gba")

    def hash(self, rom_input: RomHashInput) -> str | None:
        return GenericMd5RomHashStrategy().hash(rom_input)


class NesRomHashStrategy(RomHashStrategy):
    def matches(self, file_name: str) -> bool:
        return has_extension(file_name, "nes")

    def hash(self, rom_input: RomHashInput) -> str | None:
        return md5_hash_with_header_rule(
            rom_input,
            16,
            lambda header, bytes_read, _file_size: (
                min(16, bytes_read)
                if starts_with_magic(header, bytes_read, b"NES\x1a")
                else 0
            ),
        )


class FdsRomHashStrategy(RomHashStrategy):
    def matches(self, file_name: str) -> bool:
        return has_extension(file_name, "fds")

    def hash(self, rom_input: RomHashInput) -> str | None:
        return md5_hash_with_header_rule(
            rom_input,
            16,
            lambda header, bytes_read, _file_size: (
                min(16, bytes_read)
                if starts_with_magic(header, bytes_read, b"FDS\x1a")
                else 0
            ),
        )


class SnesRomHashStrategy(RomHashStrategy):
    def matches(self, file_name: str) -> bool:
        return has_extension(file_name, "smc", "sfc", "fig", "swc")

    def hash(self, rom_input: RomHashInput) -> str | None:
        return md5_hash_with_header_rule(
            rom_input,
            512,
            lambda _header, bytes_read, file_size: (
                512
                if bytes_read >= 512
                and file_size > 512
                and ((file_size - 512) % 8192 == 0)
                else 0
            ),
        )


class PcEngineRomHashStrategy(RomHashStrategy):
    def matches(self, file_name: str) -> bool:
        return has_extension(file_name, "pce", "sgx")

    def hash(self, rom_input: RomHashInput) -> str | None:
        return md5_hash_with_header_rule(
            rom_input,
            512,
            lambda _header, _bytes_read, file_size: (
                512 if (file_size & 512) != 0 else 0
            ),
        )


class Atari7800RomHashStrategy(RomHashStrategy):
    def matches(self, file_name: str) -> bool:
        return has_extension(file_name, "a78")

    def hash(self, rom_input: RomHashInput) -> str | None:
        return md5_hash_with_header_rule(
            rom_input,
            128,
            lambda header, bytes_read, _file_size: (
                128 if bytes_read >= 10 and header[1:10] == b"ATARI7800" else 0
            ),
        )


class AtariLynxRomHashStrategy(RomHashStrategy):
    def matches(self, file_name: str) -> bool:
        return has_extension(file_name, "lnx")

    def hash(self, rom_input: RomHashInput) -> str | None:
        return md5_hash_with_header_rule(
            rom_input,
            64,
            lambda header, bytes_read, _file_size: (
                64 if starts_with_magic(header, bytes_read, b"LYNX") else 0
            ),
        )


class SuperCassetteVisionRomHashStrategy(RomHashStrategy):
    def matches(self, file_name: str) -> bool:
        return has_extension(file_name, "cart")

    def hash(self, rom_input: RomHashInput) -> str | None:
        return md5_hash_with_header_rule(
            rom_input,
            32,
            lambda header, bytes_read, _file_size: (
                32 if starts_with_magic(header, bytes_read, b"EmuSCV") else 0
            ),
        )


class Nintendo64RomHashStrategy(RomHashStrategy):
    def matches(self, file_name: str) -> bool:
        return has_extension(file_name, "z64", "n64", "v64")

    def hash(self, rom_input: RomHashInput) -> str | None:
        try:
            digest = hashlib.md5()
            with rom_input.path.open("rb") as handle:
                first_byte = handle.read(1)
                if len(first_byte) != 1:
                    return None

                byte_order = detect_n64_byte_order(first_byte[0])
                if byte_order is None:
                    return None

                remaining = min(rom_input.file_size, N64_HASH_LIMIT_BYTES)
                if remaining <= 0:
                    return None

                buffer = bytearray(N64_BUFFER_SIZE)
                buffer[0] = first_byte[0]
                initial_read = handle.readinto(memoryview(buffer)[1:])
                buffered = 1 + (initial_read or 0)
                normalize_n64_bytes(buffer, buffered, byte_order)
                count = min(buffered, remaining)
                digest.update(buffer[:count])
                remaining -= count

                while remaining > 0:
                    read = handle.readinto(buffer)
                    if not read:
                        break
                    normalize_n64_bytes(buffer, read, byte_order)
                    count = min(read, remaining)
                    digest.update(buffer[:count])
                    remaining -= count

            return digest.hexdigest()
        except Exception:
            return None


class NintendoDsRomHashStrategy(RomHashStrategy):
    def matches(self, file_name: str) -> bool:
        return has_extension(file_name, "nds")

    def hash(self, rom_input: RomHashInput) -> str | None:
        try:
            header_offset = 0
            with rom_input.path.open("rb") as handle:
                header = read_at(handle, 0, NDS_HEADER_SIZE)
                if header is None:
                    return None

                if has_supercard_header(header):
                    header_offset = NDS_SUPERCARD_HEADER_SIZE
                    header = read_at(handle, header_offset, NDS_HEADER_SIZE)
                    if header is None:
                        return None

                arm9_addr = little_endian_int(header, 0x20)
                arm9_size = little_endian_int(header, 0x2C)
                arm7_addr = little_endian_int(header, 0x30)
                arm7_size = little_endian_int(header, 0x3C)
                icon_addr = little_endian_int(header, 0x68)

                if (
                    arm9_size < 0
                    or arm7_size < 0
                    or (arm9_size + arm7_size) > NDS_MAX_CODE_SIZE
                ):
                    return None

                digest = hashlib.md5()
                digest.update(header[:NDS_HASHED_HEADER_SIZE])

                if not hash_segment(
                    digest, handle, header_offset + arm9_addr, arm9_size
                ):
                    return None
                if not hash_segment(
                    digest, handle, header_offset + arm7_addr, arm7_size
                ):
                    return None

                icon_block = read_at(
                    handle, header_offset + icon_addr, NDS_ICON_BLOCK_SIZE
                )
                if icon_block is None:
                    return None
                if len(icon_block) < NDS_ICON_BLOCK_SIZE:
                    icon_block = icon_block + (
                        b"\x00" * (NDS_ICON_BLOCK_SIZE - len(icon_block))
                    )
                digest.update(icon_block)
                return digest.hexdigest()
        except Exception:
            return None


class PsxRomHashStrategy(RomHashStrategy):
    def matches(self, file_name: str) -> bool:
        return has_extension(file_name, "bin", "iso", "chd")

    def hash(self, rom_input: RomHashInput) -> str | None:
        if has_extension(rom_input.file_name, "chd"):
            direct_result = open_direct_chd_reader(rom_input.path)
            if direct_result.handle is not None:
                try:
                    layout = detect_iso_sector_layout(direct_result.handle)
                    if layout is None:
                        return None

                    executable = find_psx_executable(direct_result.handle, layout)
                    if executable is None:
                        return None

                    return hash_psx_executable(direct_result.handle, layout, executable)
                finally:
                    direct_result.handle.close()
            return None

        try:
            with rom_input.path.open("rb") as handle:
                layout = detect_iso_sector_layout(handle)
                if layout is None:
                    return None

                executable = find_psx_executable(handle, layout)
                if executable is None:
                    return None

                return hash_psx_executable(handle, layout, executable)
        except Exception:
            return None


class PspRomHashStrategy(RomHashStrategy):
    def matches(self, file_name: str) -> bool:
        return has_extension(file_name, "iso", "pbp")

    def hash(self, rom_input: RomHashInput) -> str | None:
        if has_extension(rom_input.file_name, "pbp"):
            return GenericMd5RomHashStrategy().hash(rom_input)

        try:
            with rom_input.path.open("rb") as handle:
                layout = detect_iso_sector_layout(handle)
                if layout is None:
                    return None

                param_record = find_file_record(handle, layout, PSP_PARAM_PATH)
                if param_record is None:
                    return None

                eboot_record = find_file_record(handle, layout, PSP_EBOOT_PATH)
                if eboot_record is None:
                    return None

                digest = hashlib.md5()
                if not update_digest_from_iso_record(
                    digest, handle, layout, param_record, PSP_MAX_HASH_BYTES
                ):
                    return None
                if not update_digest_from_iso_record(
                    digest, handle, layout, eboot_record, PSP_MAX_HASH_BYTES
                ):
                    return None
                return digest.hexdigest()
        except Exception:
            return None


ROM_HASH_STRATEGIES = [
    GameBoyAdvanceRomHashStrategy(),
    GameBoyColorRomHashStrategy(),
    GameBoyRomHashStrategy(),
    PspRomHashStrategy(),
    PsxRomHashStrategy(),
    NintendoDsRomHashStrategy(),
    Nintendo64RomHashStrategy(),
    Atari7800RomHashStrategy(),
    AtariLynxRomHashStrategy(),
    NesRomHashStrategy(),
    FdsRomHashStrategy(),
    PcEngineRomHashStrategy(),
    SuperCassetteVisionRomHashStrategy(),
    SnesRomHashStrategy(),
]


def _parse_cue_data_bin(cue_path: Path) -> str | None:
    try:
        text = cue_path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return None
    current_file: str | None = None
    for line in text.splitlines():
        stripped = line.strip()
        if stripped.upper().startswith("FILE "):
            parts = stripped.split('"')
            if len(parts) >= 3:
                current_file = parts[1]
        elif stripped.upper().startswith("TRACK "):
            if "AUDIO" not in stripped.upper() and current_file is not None:
                return current_file
    return None


def _parse_m3u_first_path(m3u_path: Path) -> Path | None:
    try:
        text = m3u_path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return None
    for line in text.splitlines():
        stripped = line.strip()
        if stripped and not stripped.startswith("#"):
            entry = Path(stripped)
            if not entry.is_absolute():
                entry = m3u_path.parent / entry
            return entry
    return None


def hash_rom(path: Path) -> str | None:
    candidates = hash_rom_candidates(path)
    return candidates[0] if candidates else None


def hash_rom_candidates(path: Path) -> list[str]:
    return hash_rom_candidates_result(path).candidates


def hash_rom_candidates_result(path: Path) -> RomHashResult:
    if has_extension(path.name, "m3u"):
        resolved = _parse_m3u_first_path(path)
        if resolved is None:
            return RomHashResult([], "Could not parse M3U or file is empty")
        path = resolved

    if has_extension(path.name, "cue"):
        bin_name = _parse_cue_data_bin(path)
        if bin_name is None:
            return RomHashResult([], "Could not find data track in CUE file")
        bin_path = path.parent / bin_name
        if not bin_path.exists():
            return RomHashResult([], f"BIN file not found: {bin_name}")
        path = bin_path

    rom_input = RomHashInput(
        file_name=path.name,
        file_size=path.stat().st_size,
        path=path,
    )
    candidates: list[str] = []
    for strategy in ROM_HASH_STRATEGIES:
        if not strategy.matches(rom_input.file_name):
            continue
        hash_value = strategy.hash(rom_input)
        if hash_value is not None:
            normalized = hash_value.strip().lower()
            if normalized and normalized not in candidates:
                candidates.append(normalized)

    if has_extension(rom_input.file_name, "chd"):
        if candidates:
            return RomHashResult(candidates)
        return RomHashResult(candidates, describe_chd_hash_failure(path))

    fallback = GenericMd5RomHashStrategy().hash(rom_input)
    if fallback is not None:
        normalized = fallback.strip().lower()
        if normalized and normalized not in candidates:
            candidates.append(normalized)

    return RomHashResult(candidates)


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


def load_libchdr() -> ctypes.CDLL | None:
    global _LIBCHDR, _LIBCHDR_ERROR
    if _LIBCHDR is not None:
        return _LIBCHDR
    if _LIBCHDR_ERROR is not None:
        return None

    candidates = [
        ctypes.util.find_library("chdr"),
        "/usr/lib/libchdr.so",
        "/usr/local/lib/libchdr.so",
        "/lib/libchdr.so",
        "/userdata/system/lib/libchdr.so",
        "/userdata/system/raofflineproxy/lib/libchdr.so",
        "/mnt/SDCARD/App/RAOfflineProxy/lib/libchdr.so",
    ]
    for candidate in candidates:
        if not candidate:
            continue
        if "/" in candidate and not os.path.isfile(candidate):
            continue
        try:
            library = ctypes.CDLL(candidate)
            configure_libchdr_api(library)
            _LIBCHDR = library
            return library
        except OSError as exc:
            if _LIBCHDR_ERROR is None:
                _LIBCHDR_ERROR = f"libchdr load failed: {exc}"
        except AttributeError as exc:
            if _LIBCHDR_ERROR is None:
                _LIBCHDR_ERROR = f"libchdr API mismatch: {exc}"

    if _LIBCHDR_ERROR is None:
        _LIBCHDR_ERROR = "libchdr shared library not found"
    return None


def configure_libchdr_api(library: ctypes.CDLL) -> None:
    library.chd_open.argtypes = [
        ctypes.c_char_p,
        ctypes.c_int,
        ctypes.c_void_p,
        ctypes.POINTER(ctypes.c_void_p),
    ]
    library.chd_open.restype = ctypes.c_int
    library.chd_close.argtypes = [ctypes.c_void_p]
    library.chd_close.restype = None
    library.chd_get_header.argtypes = [ctypes.c_void_p]
    library.chd_get_header.restype = ctypes.POINTER(ChdHeader)
    library.chd_read.argtypes = [ctypes.c_void_p, ctypes.c_uint32, ctypes.c_void_p]
    library.chd_read.restype = ctypes.c_int
    library.chd_get_metadata.argtypes = [
        ctypes.c_void_p,
        ctypes.c_uint32,
        ctypes.c_uint32,
        ctypes.c_void_p,
        ctypes.c_uint32,
        ctypes.POINTER(ctypes.c_uint32),
        ctypes.POINTER(ctypes.c_uint32),
        ctypes.c_void_p,
    ]
    library.chd_get_metadata.restype = ctypes.c_int
    library.chd_error_string.argtypes = [ctypes.c_int]
    library.chd_error_string.restype = ctypes.c_char_p


def libchdr_error_message() -> str:
    return _LIBCHDR_ERROR or "libchdr shared library not found"


def chd_error_string(library: ctypes.CDLL, error_code: int) -> str:
    raw = library.chd_error_string(error_code)
    if raw is None:
        return f"CHD error {error_code}"
    return raw.decode("utf-8", errors="replace")


def load_chd_track_info(
    library: ctypes.CDLL, chd_handle: ctypes.c_void_p, index: int
) -> ChdTrackInfo | None:
    metadata = ctypes.create_string_buffer(256)
    metadata_length = ctypes.c_uint32()
    metadata_tag = ctypes.c_uint32()

    result = library.chd_get_metadata(
        chd_handle,
        CDROM_TRACK_METADATA2_TAG,
        index,
        metadata,
        len(metadata),
        ctypes.byref(metadata_length),
        ctypes.byref(metadata_tag),
        None,
    )
    if result == CHDERR_NONE:
        return parse_chd_track_metadata(metadata.value.decode("utf-8", errors="replace"))

    result = library.chd_get_metadata(
        chd_handle,
        CDROM_TRACK_METADATA_TAG,
        index,
        metadata,
        len(metadata),
        ctypes.byref(metadata_length),
        ctypes.byref(metadata_tag),
        None,
    )
    if result == CHDERR_NONE:
        return parse_chd_track_metadata(metadata.value.decode("utf-8", errors="replace"))

    return None


def parse_chd_track_metadata(metadata: str) -> ChdTrackInfo | None:
    values: dict[str, str] = {}
    for part in metadata.strip().split():
        if ":" not in part:
            continue
        key, value = part.split(":", 1)
        values[key] = value

    try:
        track = int(values.get("TRACK", "0"))
        track_type = values.get("TYPE", "")
        frames = int(values.get("FRAMES", "0"))
        pregap = int(values.get("PREGAP", "0"))
        postgap = int(values.get("POSTGAP", "0"))
    except ValueError:
        return None

    if track <= 0 or not track_type or frames <= 0:
        return None

    return ChdTrackInfo(track, track_type, frames, pregap, postgap)


def pad_track_frames(frames: int) -> int:
    return (frames + 3) & ~3


def open_direct_chd_reader(path: Path) -> ChdOpenResult:
    library = load_libchdr()
    if library is None:
        return ChdOpenResult(None, libchdr_error_message())

    chd_handle = ctypes.c_void_p()
    result = library.chd_open(
        os.fsencode(str(path)),
        CHD_OPEN_READ,
        ctypes.c_void_p(),
        ctypes.byref(chd_handle),
    )
    if result != CHDERR_NONE:
        error = chd_error_string(library, result)
        return ChdOpenResult(None, f"libchdr open failed: {error}")

    try:
        header = library.chd_get_header(chd_handle)
        if not header:
            return ChdOpenResult(None, "libchdr missing CHD header")

        hunk_bytes = header.contents.hunkbytes
        if hunk_bytes <= 0:
            return ChdOpenResult(None, "libchdr reported invalid CHD hunk size")

        track_index = 0
        accumulated_frames = 0
        while True:
            track = load_chd_track_info(library, chd_handle, track_index)
            if track is None:
                break
            if track.type != "AUDIO":
                return ChdOpenResult(
                    DirectChdReader(
                        LibChdrDiscHandle(
                            library,
                            chd_handle,
                            hunk_bytes,
                            accumulated_frames,
                            track.frames,
                        )
                    )
                )
            accumulated_frames += pad_track_frames(track.frames)
            track_index += 1

        return ChdOpenResult(None, "libchdr found no data track")
    except Exception as exc:
        return ChdOpenResult(None, f"libchdr open failed: {exc}")
    finally:
        if not isinstance(locals().get("track", None), ChdTrackInfo) or (
            locals().get("track") is not None and locals().get("track").type == "AUDIO"
        ):
            if chd_handle:
                library.chd_close(chd_handle)


def describe_chd_hash_failure(path: Path) -> str:
    direct_result = open_direct_chd_reader(path)
    if direct_result.handle is not None:
        direct_result.handle.close()
        return "CHD hash failed after direct libchdr read"
    if direct_result.error is not None:
        return direct_result.error
    return "libchdr shared library not found"


def has_extension(file_name: str, *extensions: str) -> bool:
    lower_name = file_name.lower()
    return any(lower_name.endswith(f".{extension.lower()}") for extension in extensions)


def starts_with_magic(header: bytes, bytes_read: int, magic: bytes) -> bool:
    return bytes_read >= len(magic) and header[: len(magic)] == magic


def md5_hash_with_header_rule(
    rom_input: RomHashInput,
    header_length: int,
    bytes_to_skip,
) -> str | None:
    try:
        digest = hashlib.md5()
        with rom_input.path.open("rb") as handle:
            header = handle.read(header_length)
            header_bytes_read = len(header)
            header_bytes_to_skip = max(
                0,
                min(
                    bytes_to_skip(header, header_bytes_read, rom_input.file_size),
                    header_bytes_read,
                ),
            )
            if header_bytes_read > header_bytes_to_skip:
                digest.update(header[header_bytes_to_skip:header_bytes_read])

            while True:
                chunk = handle.read(8192)
                if not chunk:
                    break
                digest.update(chunk)

        return digest.hexdigest()
    except Exception:
        return None


def detect_n64_byte_order(first_byte: int) -> str | None:
    if first_byte == 0x80:
        return "big"
    if first_byte == 0x37:
        return "byte_swapped"
    if first_byte == 0x40:
        return "little"
    if first_byte in {0xE8, 0x22}:
        return "disk_drive"
    return None


def normalize_n64_bytes(buffer: bytearray, bytes_read: int, byte_order: str) -> None:
    if byte_order == "byte_swapped":
        byteswap16(buffer, bytes_read)
        return

    if byte_order == "little":
        byteswap32(buffer, bytes_read)


def byteswap16(buffer: bytearray, bytes_read: int) -> None:
    index = 0
    while index + 1 < bytes_read:
        buffer[index], buffer[index + 1] = buffer[index + 1], buffer[index]
        index += 2


def byteswap32(buffer: bytearray, bytes_read: int) -> None:
    index = 0
    while index + 3 < bytes_read:
        b0 = buffer[index]
        b1 = buffer[index + 1]
        b2 = buffer[index + 2]
        b3 = buffer[index + 3]
        buffer[index] = b3
        buffer[index + 1] = b2
        buffer[index + 2] = b1
        buffer[index + 3] = b0
        index += 4


def read_at(handle, offset: int, length: int) -> bytes | None:
    handle.seek(offset)
    data = handle.read(length)
    return data if data else None


def hash_segment(digest, handle, offset: int, size: int) -> bool:
    if size == 0:
        return True
    data = read_at(handle, offset, size)
    if data is None or len(data) != size:
        return False
    digest.update(data)
    return True


def has_supercard_header(header: bytes) -> bool:
    return (
        len(header) >= NDS_HEADER_SIZE
        and header[0] == 0x2E
        and header[1] == 0x00
        and header[2] == 0x00
        and header[3] == 0xEA
        and header[0xB0] == 0x44
        and header[0xB1] == 0x46
        and header[0xB2] == 0x96
        and header[0xB3] == 0x00
    )


def detect_iso_sector_layout(handle) -> SectorLayout | None:
    candidates = [
        SectorLayout(ISO_SECTOR, 0, 0),
        SectorLayout(RAW_2352_SECTOR, 24, 0),
        SectorLayout(RAW_2352_SECTOR, 16, 0),
        SectorLayout(MODE2_2336_SECTOR, 8, 0),
    ]
    for layout in candidates:
        detected = detect_primary_volume_descriptor(handle, layout)
        if detected is not None:
            return detected
    return None


def detect_primary_volume_descriptor(
    handle, layout: SectorLayout
) -> SectorLayout | None:
    for physical_sector in range(PVD_SECTOR, PVD_SECTOR + PVD_SCAN_WINDOW + 1):
        sector = read_physical_sector(handle, layout, physical_sector)
        if sector is None:
            continue
        if len(sector) >= 6 and sector[0] == 1 and sector[1:6] == PVD_MAGIC:
            return SectorLayout(
                layout.raw_sector_size, layout.data_offset, physical_sector - PVD_SECTOR
            )
    return None


def read_physical_sector(
    handle, layout: SectorLayout, physical_sector_index: int
) -> bytes | None:
    offset = physical_sector_index * layout.raw_sector_size
    raw_buffer = read_at(handle, offset, layout.raw_sector_size)
    if raw_buffer is None or len(raw_buffer) < layout.data_offset + ISO_SECTOR:
        return None
    return raw_buffer[layout.data_offset : layout.data_offset + ISO_SECTOR]


def read_sector(handle, layout: SectorLayout, sector_index: int) -> bytes | None:
    return read_physical_sector(handle, layout, sector_index + layout.sector_bias)


def read_file_bytes(
    handle, layout: SectorLayout, start_sector: int, size: int
) -> bytes | None:
    chunks: list[bytes] = []
    remaining = size
    sector_index = start_sector
    while remaining > 0:
        sector = read_sector(handle, layout, sector_index)
        if sector is None:
            return None
        count = min(remaining, len(sector))
        chunks.append(sector[:count])
        remaining -= count
        sector_index += 1
    return b"".join(chunks)


def read_file_text(handle, layout: SectorLayout, path: str) -> str | None:
    record = find_file_record(handle, layout, path)
    if record is None:
        return None
    data = read_file_bytes(handle, layout, record.sector, record.size)
    if data is None:
        return None
    return data.decode("ascii", errors="ignore")


def find_file_record(handle, layout: SectorLayout, path: str) -> IsoFileRecord | None:
    segments = [segment for segment in path.replace("/", "\\").split("\\") if segment]
    if not segments:
        return None

    root_record = read_root_directory_record(handle, layout)
    if root_record is None:
        return None

    current_sector = root_record.sector
    current_size = root_record.size
    for index, segment in enumerate(segments):
        record = find_directory_entry(
            handle, layout, current_sector, current_size, segment
        )
        if record is None:
            return None
        if index == len(segments) - 1:
            return record
        if not record.is_directory:
            return None
        current_sector = record.sector
        current_size = record.size

    return None


def read_root_directory_record(handle, layout: SectorLayout) -> IsoFileRecord | None:
    sector = read_sector(handle, layout, PVD_SECTOR)
    if sector is None or len(sector) < 190:
        return None
    return parse_directory_record(sector, 156)


def find_directory_entry(
    handle,
    layout: SectorLayout,
    directory_sector: int,
    directory_size: int,
    target_name: str,
) -> IsoFileRecord | None:
    logical_block_size = read_logical_block_size(handle, layout)
    sectors_to_scan = (
        int(math.ceil(directory_size / logical_block_size))
        if logical_block_size > 0
        else 0
    )
    sector_index = directory_sector
    for _ in range(sectors_to_scan):
        sector = read_sector(handle, layout, sector_index)
        if sector is None:
            return None
        offset = 0
        while offset < logical_block_size and offset < len(sector):
            length = sector[offset]
            if length == 0:
                break
            record = parse_directory_record(sector, offset)
            if record is not None and names_equal(record.name, target_name):
                return record
            offset += length
        sector_index += 1
    return None


def read_logical_block_size(handle, layout: SectorLayout) -> int:
    pvd = read_sector(handle, layout, PVD_SECTOR)
    if pvd is None:
        return ISO_SECTOR
    block_size = little_endian_short(pvd, PVD_LOGICAL_BLOCK_SIZE_OFFSET)
    return block_size if 1 <= block_size <= ISO_SECTOR else ISO_SECTOR


def parse_directory_record(sector: bytes, offset: int) -> IsoFileRecord | None:
    if offset + 34 > len(sector):
        return None
    length = sector[offset]
    if length == 0 or offset + length > len(sector):
        return None

    sector_number = little_endian_int(sector, offset + 2)
    size = little_endian_int(sector, offset + 10)
    flags = sector[offset + 25]
    name_length = sector[offset + 32]
    if offset + 33 + name_length > len(sector):
        return None
    raw_name = sector[offset + 33 : offset + 33 + name_length]
    if name_length == 1 and raw_name in {b"\x00", b"\x01"}:
        return IsoFileRecord(sector_number, size, "", bool(flags & 0x02))

    name = raw_name.decode("ascii", errors="ignore").split(";", 1)[0]
    return IsoFileRecord(sector_number, size, name, bool(flags & 0x02))


def names_equal(record_name: str, target_name: str) -> bool:
    return record_name.lower() == target_name.split(";", 1)[0].lower()


def find_psx_executable(handle, layout: SectorLayout) -> PsxExecutable | None:
    system_cnf = read_file_text(handle, layout, "SYSTEM.CNF") or ""
    boot_path = parse_psx_boot_path(system_cnf) or "PSX.EXE"
    executable_record = find_file_record(handle, layout, boot_path)
    if executable_record is None:
        return None
    return PsxExecutable(boot_path, executable_record.sector, executable_record.size)


def parse_psx_boot_path(system_cnf: str) -> str | None:
    for line in system_cnf.splitlines():
        trimmed = line.lstrip()
        if not trimmed.lower().startswith("boot"):
            continue
        if len(trimmed) > 4 and trimmed[4] not in {" ", "\t", "="}:
            continue
        after_key = trimmed[4:]
        raw_value = after_key.split("=", 1)[1].lstrip() if "=" in after_key else ""
        if not raw_value:
            return None
        path = raw_value
        if path.lower().startswith("cdrom:"):
            path = path[6:]
        path = path.lstrip("\\")
        for separator in (" ", "\t", ";"):
            if separator in path:
                path = path.split(separator, 1)[0]
        return path or None
    return None


def hash_psx_executable(
    handle, layout: SectorLayout, executable: PsxExecutable
) -> str | None:
    digest = hashlib.md5()
    digest.update(executable.path.encode("ascii", errors="ignore"))

    first_sector = read_sector(handle, layout, executable.sector)
    if first_sector is None:
        return None

    bytes_to_hash = min(executable.size, PSX_MAX_HASH_BYTES)
    if starts_with_magic(first_sector, len(first_sector), PSX_EXE_MAGIC):
        body_size = little_endian_int(first_sector, PSX_EXE_BODY_SIZE_OFFSET)
        bytes_to_hash = min(body_size + PSX_EXE_HEADER_SIZE, PSX_MAX_HASH_BYTES)

    remaining = bytes_to_hash
    sector_index = executable.sector
    while remaining > 0:
        sector = read_sector(handle, layout, sector_index)
        if sector is None:
            return None
        count = min(remaining, len(sector))
        digest.update(sector[:count])
        remaining -= count
        sector_index += 1
    return digest.hexdigest()


def update_digest_from_iso_record(
    digest, handle, layout: SectorLayout, record: IsoFileRecord, limit: int
) -> bool:
    remaining = min(record.size, limit)
    sector_index = record.sector
    while remaining > 0:
        sector = read_sector(handle, layout, sector_index)
        if sector is None:
            return False
        count = min(remaining, len(sector))
        digest.update(sector[:count])
        remaining -= count
        sector_index += 1
    return True


def little_endian_short(data: bytes, offset: int) -> int:
    if offset + 2 > len(data):
        return 0
    return data[offset] | (data[offset + 1] << 8)


def little_endian_int(data: bytes, offset: int) -> int:
    if offset + 4 > len(data):
        return 0
    return (
        data[offset]
        | (data[offset + 1] << 8)
        | (data[offset + 2] << 16)
        | (data[offset + 3] << 24)
    )
