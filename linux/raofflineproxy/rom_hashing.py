import hashlib
from dataclasses import dataclass
from pathlib import Path

N64_HASH_LIMIT_BYTES = 64 * 1024 * 1024
N64_BUFFER_SIZE = 65536


@dataclass
class RomHashInput:
    file_name: str
    file_size: int
    path: Path


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


ROM_HASH_STRATEGIES = [
    Nintendo64RomHashStrategy(),
    Atari7800RomHashStrategy(),
    AtariLynxRomHashStrategy(),
    NesRomHashStrategy(),
    FdsRomHashStrategy(),
    PcEngineRomHashStrategy(),
    SuperCassetteVisionRomHashStrategy(),
    SnesRomHashStrategy(),
]


def hash_rom(path: Path) -> str | None:
    rom_input = RomHashInput(
        file_name=path.name,
        file_size=path.stat().st_size,
        path=path,
    )
    for strategy in ROM_HASH_STRATEGIES:
        if not strategy.matches(rom_input.file_name):
            continue
        hash_value = strategy.hash(rom_input)
        if hash_value is not None:
            return hash_value
    return GenericMd5RomHashStrategy().hash(rom_input)


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
    }


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
