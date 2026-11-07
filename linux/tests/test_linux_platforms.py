import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from linux.raofflineproxy import platform, platforms
from linux.raofflineproxy.emulators import retroarch_cfg
from linux.raofflineproxy.platforms import (
    allium,
    darkos,
    generic,
    knulli,
    muos,
    onion,
    rocknix,
    spruce,
    windows,
)


class CurrentPlatformTests(unittest.TestCase):
    def _detect_only(self, matching) -> None:
        for platform in platforms.PLATFORMS:
            patcher = patch.object(platform, "detect", return_value=platform is matching)
            patcher.start()
            self.addCleanup(patcher.stop)

    def test_falls_back_to_generic_when_nothing_matches(self) -> None:
        self._detect_only(None)
        self.assertIs(platforms.current(), generic.PLATFORM)

    def test_returns_the_matching_platform(self) -> None:
        for platform in platforms.PLATFORMS:
            with self.subTest(platform=platform.id):
                with patch.object(platform, "detect", return_value=True):
                    for other in platforms.PLATFORMS:
                        if other is not platform:
                            patcher = patch.object(other, "detect", return_value=False)
                            patcher.start()
                            self.addCleanup(patcher.stop)
                    self.assertIs(platforms.current(), platform)

    def test_first_match_wins_in_detection_order(self) -> None:
        with patch.object(allium.PLATFORM, "detect", return_value=True):
            with patch.object(onion.PLATFORM, "detect", return_value=True):
                self.assertIs(platforms.current(), allium.PLATFORM)

    def test_ids_are_unique_and_resolvable(self) -> None:
        ids = [platform.id for platform in platforms.PLATFORMS]
        self.assertEqual(len(ids), len(set(ids)))
        for platform in platforms.PLATFORMS:
            self.assertIs(platforms.by_id(platform.id), platform)
        self.assertIsNone(platforms.by_id("nope"))

    def test_all_platforms_are_registered(self) -> None:
        modules = (allium, darkos, knulli, muos, onion, rocknix, spruce, windows)
        self.assertEqual({module.PLATFORM for module in modules}, set(platforms.PLATFORMS))


class RetroarchCfgTests(unittest.TestCase):
    def test_every_platform_resolves_a_retroarch_cfg(self) -> None:
        # A detected platform without cfg candidates once made the lookup raise instead of
        # falling back, which broke dArkOS.
        for module in (allium, darkos, knulli, muos, onion, rocknix, spruce, windows, generic):
            with self.subTest(platform=module.PLATFORM.id):
                for candidate in platforms.PLATFORMS:
                    patcher = patch.object(
                        candidate, "detect", return_value=candidate is module.PLATFORM
                    )
                    patcher.start()
                    self.addCleanup(patcher.stop)
                with patch.dict("os.environ", {}, clear=False):
                    self.assertTrue(retroarch_cfg.detect_retroarch_cfg())

    def test_darkos_keeps_retroarch_in_the_ark_home(self) -> None:
        self.assertEqual(
            darkos.PLATFORM.retroarch_cfg_candidates(darkos.HOME),
            (darkos.HOME / ".config" / "retroarch" / "retroarch.cfg",),
        )


class PlatformHookTests(unittest.TestCase):
    def test_hooks_default_to_doing_nothing(self) -> None:
        self.assertIsNone(generic.PLATFORM.own_credentials())
        self.assertIsNone(generic.PLATFORM.history_source())
        self.assertIsNone(generic.PLATFORM.recent_rom_paths())
        self.assertFalse(generic.PLATFORM.install_service_unit())
        self.assertIsNone(generic.PLATFORM.remove_stale_files())

    def test_knulli_removes_the_stale_game_hook(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            hook = Path(temp_dir) / "RAOfflineProxy_game_hook.sh"
            hook.write_text("#!/bin/sh\n", encoding="utf-8")
            with patch.object(knulli, "STALE_HOOK_PATH", hook):
                knulli.PLATFORM.remove_stale_files()
                knulli.PLATFORM.remove_stale_files()
            self.assertFalse(hook.exists())

    def test_darkos_installs_its_service_unit(self) -> None:
        with patch.object(darkos, "systemd_install_service") as install:
            self.assertTrue(darkos.PLATFORM.install_service_unit())
        install.assert_called_once_with()

    def test_muos_reads_its_own_history(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            rom = root / "game.gba"
            rom.write_text("rom", encoding="utf-8")
            history = root / "history"
            history.mkdir()
            (history / "one.cfg").write_text(f"{rom}\ngba\nGame", encoding="utf-8")
            (history / "again.cfg").write_text(f"{rom}\ngba\nGame", encoding="utf-8")
            (history / "gone.cfg").write_text(f"{root / 'missing.gba'}\ngba\nGone", encoding="utf-8")
            with patch.object(muos, "HISTORY_DIR", history):
                self.assertEqual(muos.PLATFORM.history_source(), history)
                self.assertEqual(muos.PLATFORM.recent_rom_paths(), [rom])

    def test_muos_without_a_history_dir_defers_to_retroarch(self) -> None:
        with patch.object(muos, "HISTORY_DIR", Path("/nonexistent/history")):
            self.assertIsNone(muos.PLATFORM.history_source())
            self.assertIsNone(muos.PLATFORM.recent_rom_paths())


class UpdateChannelTests(unittest.TestCase):
    def test_update_channels_are_the_platforms_that_ship_a_release_asset(self) -> None:
        channels = {p.update_channel for p in platforms.PLATFORMS if p.update_channel}
        self.assertEqual(
            channels, {"allium", "darkos", "knulli", "muos", "onion", "rocknix", "spruce"}
        )
        self.assertIsNone(windows.PLATFORM.update_channel)
        self.assertIsNone(generic.PLATFORM.update_channel)

    def test_asset_names_match_the_channel_and_suffix(self) -> None:
        from linux.raofflineproxy import update

        assets = [
            {"name": "RAOfflineProxy-Onion-v1.zip", "browser_download_url": "https://x/onion.zip"},
            {"name": "RAOfflineProxy-muOS-v1.muxapp", "browser_download_url": "https://x/muos"},
            {"name": "RAOfflineProxy-Knulli-v1-Install.sh", "browser_download_url": "https://x/k.sh"},
        ]
        self.assertEqual(update.find_platform_asset_url("onion", assets), "https://x/onion.zip")
        self.assertEqual(update.find_platform_asset_url("muos", assets), "https://x/muos")
        self.assertEqual(update.find_platform_asset_url("knulli", assets), "https://x/k.sh")
        self.assertIsNone(update.find_platform_asset_url("windows", assets))
        self.assertIsNone(update.find_platform_asset_url("nope", assets))

    def test_validate_platform_rejects_unknown_and_channelless_platforms(self) -> None:
        from linux.raofflineproxy import update

        self.assertEqual(update.validate_platform(" Spruce "), "spruce")
        for name in ("windows", "generic", "nope"):
            with self.assertRaises(ValueError):
                update.validate_platform(name)


class ServiceHookTests(unittest.TestCase):
    def test_only_dArkOS_owns_the_service(self) -> None:
        for module in (allium, knulli, muos, onion, rocknix, spruce, windows, generic):
            self.assertIsNone(module.PLATFORM.service_status())
        with patch.object(darkos, "darkos_service_status", return_value={"running": True}):
            self.assertEqual(darkos.PLATFORM.service_status(), {"running": True})

    def test_only_windows_has_a_process_backend(self) -> None:
        for module in (allium, darkos, knulli, muos, onion, rocknix, spruce, generic):
            self.assertIsNone(module.PLATFORM.process_backend())
        # Importing the Windows process module needs ctypes.WinDLL, so only the override is checked.
        self.assertIsNot(
            type(windows.PLATFORM).process_backend, platforms.base.Platform.process_backend
        )


class StartupScriptTests(unittest.TestCase):
    def test_generic_has_no_startup_script(self) -> None:
        self.assertIsNone(generic.PLATFORM.startup_script())

    def test_resolve_follows_the_detected_platform(self) -> None:
        for module in (allium, knulli, muos, onion, rocknix):
            with self.subTest(platform=module.PLATFORM.id):
                with patch.object(platforms, "current", return_value=module.PLATFORM):
                    self.assertEqual(
                        platform.resolve_startup_script_path({}), module.STARTUP_SCRIPT
                    )
        with patch.object(platforms, "current", return_value=darkos.PLATFORM):
            self.assertEqual(platform.resolve_startup_script_path({}), darkos.SERVICE_UNIT)

    def test_resolve_is_none_without_a_platform(self) -> None:
        with patch.object(platforms, "current", return_value=generic.PLATFORM):
            self.assertIsNone(platform.resolve_startup_script_path({}))

    def test_configured_script_wins(self) -> None:
        self.assertEqual(
            platform.resolve_startup_script_path({"startup_script": "/x/boot.sh"}).as_posix(),
            "/x/boot.sh",
        )


if __name__ == "__main__":
    unittest.main()
