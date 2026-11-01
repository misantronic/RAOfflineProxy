import struct
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from linux.raofflineproxy import config, duckstation_cfg, smart_cache

SETTINGS_INI = """[Main]
SettingsVersion = 3

[Achievements]
Host = not-this-section

[Cheevos]
ChallengeMode = true
Enabled = true
Username = player

[Logging]
LogLevel = Info
"""


def cache_entry(path: str, serial: str) -> bytes:
    def text(value: str) -> bytes:
        data = value.encode("utf-8")
        return struct.pack("<I", len(data)) + data

    return (
        struct.pack("<BB", 0, 1) + text(path) + text(serial) + text("Title")
        + struct.pack("<QqQQb", 1, 2, 3, 4, -1) + bytes(16)
    )


def gamelist_cache(*entries: tuple[str, str], version: int = smart_cache.DUCKSTATION_CACHE_VERSION) -> bytes:
    return struct.pack("<II", smart_cache.DUCKSTATION_CACHE_SIGNATURE, version) + b"".join(
        cache_entry(path, serial) for path, serial in entries
    )


class DuckStationCfgTests(unittest.TestCase):
    def setUp(self) -> None:
        self.folder = Path(tempfile.mkdtemp())
        self.ini = self.folder / "settings.ini"
        self.ini.write_text(SETTINGS_INI, encoding="utf-8")
        self.config = {"duckstation_ini": str(self.ini), "proxy_port": 18080}

    def test_patching_changes_only_the_cheevos_section(self) -> None:
        result = duckstation_cfg.patch_duckstation_ini(self.config)
        self.assertEqual(result["previous"], {"Host": None, "ChallengeMode": "true"})
        text = self.ini.read_text(encoding="utf-8")
        self.assertIn("[Achievements]\nHost = not-this-section", text)
        cheevos = text.split("[Cheevos]")[1].split("[Logging]")[0]
        self.assertIn("Host = 127.0.0.1:18080", cheevos)
        self.assertIn("ChallengeMode = false", cheevos)
        self.assertIn("Username = player", cheevos)

    def test_reverting_after_patching_twice_restores_the_file(self) -> None:
        patch_state: dict = {}
        for _ in range(2):
            duckstation_cfg.store_duckstation_previous(
                patch_state, duckstation_cfg.patch_duckstation_ini(self.config)
            )
        duckstation_cfg.revert_duckstation_ini(self.config, patch_state["duckstation_previous"])
        self.assertEqual(self.ini.read_text(encoding="utf-8"), SETTINGS_INI)

    def test_no_duckstation_means_nothing_happens(self) -> None:
        with mock.patch.object(config, "running_on_windows", return_value=False):
            self.assertIsNone(config.detect_duckstation_ini({}))
            self.assertFalse(duckstation_cfg.patch_duckstation_ini({})["exists"])


class DuckStationRecentGamesTests(unittest.TestCase):
    def test_the_cache_maps_serials_to_files(self) -> None:
        data = gamelist_cache(("C:/ps1/a.chd", "SLUS-00958"), ("C:/ps1/b.chd", "SCUS-94503"))
        self.assertEqual(
            smart_cache.parse_duckstation_gamelist_cache(data),
            {"SLUS-00958": Path("C:/ps1/a.chd"), "SCUS-94503": Path("C:/ps1/b.chd")},
        )

    def test_an_unknown_cache_version_is_not_guessed_at(self) -> None:
        data = gamelist_cache(("C:/ps1/a.chd", "SLUS-00958"), version=99)
        self.assertEqual(smart_cache.parse_duckstation_gamelist_cache(data), {})

    def test_a_truncated_cache_keeps_the_entries_before_the_damage(self) -> None:
        data = gamelist_cache(("C:/ps1/a.chd", "SLUS-00958"), ("C:/ps1/b.chd", "SCUS-94503"))
        self.assertEqual(
            list(smart_cache.parse_duckstation_gamelist_cache(data[:-10])), ["SLUS-00958"]
        )

    def test_recent_games_are_the_played_files_that_exist(self) -> None:
        root = Path(tempfile.mkdtemp())
        (root / "cache").mkdir()
        game = root / "recent.chd"
        game.write_bytes(b"disc")
        (root / "settings.ini").write_text("", encoding="utf-8")
        (root / "playtime.dat").write_text(
            "SLUS-00958   19   1793546987\nSLUS-00292   5   1793546000\n", encoding="utf-8"
        )
        (root / "cache" / "gamelist.cache").write_bytes(
            gamelist_cache((str(game), "SLUS-00958"), (str(root / "gone.chd"), "SLUS-00292"))
        )
        paths = smart_cache._load_duckstation_recent_paths({"duckstation_ini": str(root / "settings.ini")})
        self.assertEqual(paths, [game])


if __name__ == "__main__":
    unittest.main()
