import os
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from linux.raofflineproxy import config
from linux.raofflineproxy.emulators import dolphin_cfg, ppsspp_cfg, retroarch_cfg
from linux.raofflineproxy.platforms import darkos as darkos_platform
from linux.raofflineproxy.platforms import windows as windows_platform
from linux.raofflineproxy.windows import paths as windows_paths


class WindowsConfigPathTests(unittest.TestCase):
    """Windows detection is gated on the Windows platform's detect(), so these patch it and
    point APPDATA/LOCALAPPDATA/home at a temp dir; they run on any host."""

    def setUp(self) -> None:
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.appdata = self.root / "Roaming"
        self.local_appdata = self.root / "Local"
        self.home = self.root / "home"

        env = mock.patch.dict(
            os.environ,
            {"APPDATA": str(self.appdata), "LOCALAPPDATA": str(self.local_appdata)},
        )
        env.start()
        self.addCleanup(env.stop)
        for key in (
            "RAOFFLINEPROXY_CONFIG_DIR",
            "XDG_CONFIG_HOME",
            "RAOFFLINEPROXY_RETROARCH_CFG",
            "RAOFFLINEPROXY_DOLPHIN_CONFIG_DIR",
            "RAOFFLINEPROXY_PPSSPP_INI",
        ):
            os.environ.pop(key, None)

        for target, value in (
            ("CONFIG_DIR", self.local_appdata / "RAOfflineProxy"),
        ):
            patcher = mock.patch.object(config, target, value)
            patcher.start()
            self.addCleanup(patcher.stop)

        original_exists = Path.exists

        def no_linux_platforms(path: Path) -> bool:
            if path.as_posix() in ("/userdata", "/storage", "/mnt/SDCARD"):
                return False
            return original_exists(path)

        for patcher in (
            mock.patch.object(Path, "exists", no_linux_platforms),
            mock.patch.object(Path, "home", return_value=self.home),
        ):
            patcher.start()
            self.addCleanup(patcher.stop)

    def _on_windows(self, value: bool = True):
        return mock.patch.object(windows_platform.PLATFORM, "detect", return_value=value)

    def _touch(self, path: Path) -> Path:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("", encoding="utf-8")
        return path

    def test_resolve_config_dir_uses_local_appdata(self) -> None:
        with mock.patch.object(darkos_platform, "HOME", self.root / "missing-ark"):
            with self._on_windows():
                self.assertEqual(
                    config.resolve_config_dir(), self.local_appdata / "RAOfflineProxy"
                )

    def test_resolve_config_dir_explicit_override_outranks_windows(self) -> None:
        os.environ["RAOFFLINEPROXY_CONFIG_DIR"] = str(self.root / "custom")
        with self._on_windows():
            self.assertEqual(config.resolve_config_dir(), self.root / "custom")

    def test_detect_retroarch_cfg_finds_the_appdata_install(self) -> None:
        cfg = self._touch(self.appdata / "RetroArch" / "retroarch.cfg")
        with self._on_windows():
            self.assertEqual(retroarch_cfg.detect_retroarch_cfg(), str(cfg))

    def test_detect_retroarch_cfg_falls_back_to_a_placeholder_when_nothing_exists(self) -> None:
        """The shared patcher refuses a missing cfg, so with no RetroArch the
        proxy gets an empty one in its data folder to patch harmlessly."""
        with mock.patch.object(
            windows_paths,
            "retroarch_cfg_candidates",
            return_value=(
                self.appdata / "RetroArch" / "retroarch.cfg",
                self.root / "RetroArch-Win64" / "retroarch.cfg",
            ),
        ):
            with self._on_windows():
                detected = Path(retroarch_cfg.detect_retroarch_cfg())
        self.assertEqual(detected, self.local_appdata / "RAOfflineProxy" / "disabled-emulators" / "retroarch.cfg")
        self.assertTrue(detected.is_file())
        self.assertEqual(detected.read_text(encoding="utf-8"), "")

    def test_detect_retroarch_cfg_env_override_outranks_windows(self) -> None:
        self._touch(self.appdata / "RetroArch" / "retroarch.cfg")
        os.environ["RAOFFLINEPROXY_RETROARCH_CFG"] = str(self.root / "portable" / "retroarch.cfg")
        with self._on_windows():
            self.assertEqual(
                retroarch_cfg.detect_retroarch_cfg(), str(self.root / "portable" / "retroarch.cfg")
            )

    def test_windows_retroarch_cfg_is_ignored_off_windows(self) -> None:
        cfg = self._touch(self.appdata / "RetroArch" / "retroarch.cfg")
        with self._on_windows(False):
            self.assertNotEqual(retroarch_cfg.detect_retroarch_cfg(), str(cfg))

    # PCSX2: config_for_exe (what the Emulators screen records for a found exe).

    def _pcsx2_exe(self) -> Path:
        return self._touch(self.root / "pcsx2" / "pcsx2-qt.exe")

    def test_pcsx2_portable_copy_uses_its_own_inis_folder(self) -> None:
        from linux.raofflineproxy.windows import emulators

        exe = self._pcsx2_exe()
        portable = self._touch(exe.parent / "inis" / "PCSX2.ini")
        self._touch(self.home / "Documents" / "PCSX2" / "inis" / "PCSX2.ini")
        self.assertEqual(emulators.config_for_exe(emulators.PCSX2, exe), portable)

    def test_pcsx2_installed_copy_uses_documents(self) -> None:
        from linux.raofflineproxy.windows import emulators

        documents = self._touch(self.home / "Documents" / "PCSX2" / "inis" / "PCSX2.ini")
        self.assertEqual(emulators.config_for_exe(emulators.PCSX2, self._pcsx2_exe()), documents)

    def test_pcsx2_before_its_first_run_still_points_at_documents(self) -> None:
        from linux.raofflineproxy.windows import emulators

        self.assertEqual(
            emulators.config_for_exe(emulators.PCSX2, self._pcsx2_exe()),
            self.home / "Documents" / "PCSX2" / "inis" / "PCSX2.ini",
        )

    def test_detect_dolphin_config_dir_prefers_documents_when_present(self) -> None:
        documents_config = self.home / "Documents" / "Dolphin Emulator" / "Config"
        documents_config.mkdir(parents=True)
        (self.appdata / "Dolphin Emulator" / "Config").mkdir(parents=True)
        with self._on_windows():
            self.assertEqual(dolphin_cfg.detect_dolphin_config_dir({}), str(documents_config))

    def test_detect_dolphin_config_dir_uses_appdata(self) -> None:
        appdata_config = self.appdata / "Dolphin Emulator" / "Config"
        appdata_config.mkdir(parents=True)
        with self._on_windows():
            self.assertEqual(dolphin_cfg.detect_dolphin_config_dir({}), str(appdata_config))
            self.assertEqual(
                dolphin_cfg.detect_dolphin_ini({}),
                str(appdata_config / "RetroAchievements.ini"),
            )

    def test_detect_dolphin_config_dir_is_none_without_a_user_dir(self) -> None:
        with self._on_windows():
            self.assertIsNone(dolphin_cfg.detect_dolphin_config_dir({}))

    def test_detect_dolphin_config_dir_configured_value_outranks_windows(self) -> None:
        (self.appdata / "Dolphin Emulator" / "Config").mkdir(parents=True)
        portable = str(self.root / "Dolphin-x64" / "User" / "Config")
        with self._on_windows():
            self.assertEqual(
                dolphin_cfg.detect_dolphin_config_dir({"dolphin_config_dir": portable}), portable
            )

    def test_detect_ppsspp_ini_uses_documents(self) -> None:
        ini = self._touch(self.home / "Documents" / "PPSSPP" / "PSP" / "SYSTEM" / "ppsspp.ini")
        with self._on_windows():
            self.assertEqual(ppsspp_cfg.detect_ppsspp_ini({}), str(ini))

    def test_detect_ppsspp_ini_is_none_when_absent(self) -> None:
        with self._on_windows():
            self.assertIsNone(ppsspp_cfg.detect_ppsspp_ini({}))


if __name__ == "__main__":
    unittest.main()
