import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from linux.raofflineproxy import platform, platforms
from linux.raofflineproxy.emulators import retroarch_cfg
from linux.raofflineproxy.platforms import miyoo
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


class MenuHookTests(unittest.TestCase):
    def test_uninstall_launchers(self) -> None:
        self.assertEqual(
            muos.PLATFORM.uninstall_launcher(False), muos.APP_DIR / "uninstall.sh"
        )
        self.assertEqual(
            rocknix.PLATFORM.uninstall_launcher(False),
            rocknix.LAUNCHER.parent / "raofflineproxy-uninstall",
        )
        for module in (knulli, darkos):
            with self.subTest(platform=module.PLATFORM.id):
                self.assertIsNotNone(module.PLATFORM.uninstall_launcher(False))
                self.assertIsNone(module.PLATFORM.uninstall_launcher(True))
        for module in (allium, onion, spruce, windows, generic):
            with self.subTest(platform=module.PLATFORM.id):
                self.assertIsNone(module.PLATFORM.uninstall_launcher(False))

    def test_update_install_layouts(self) -> None:
        self.assertEqual(
            (onion.PLATFORM.app_dir(), onion.PLATFORM.update_archive_root),
            (onion.APP_DIR, "App"),
        )
        self.assertEqual(
            (spruce.PLATFORM.app_dir(), spruce.PLATFORM.update_archive_root),
            (onion.APP_DIR, "App"),
        )
        self.assertEqual(
            (allium.PLATFORM.app_dir(), allium.PLATFORM.update_archive_root),
            (allium.APP_DIR, "Apps"),
        )
        self.assertEqual(muos.PLATFORM.update_install_kind, "archive")
        self.assertEqual(muos.PLATFORM.update_archive_root, "")
        self.assertTrue(muos.PLATFORM.preserve_app_data_on_update)
        self.assertTrue(muos.PLATFORM.delete_update_archive)
        self.assertEqual(
            muos.PLATFORM.update_archive_path(), muos.APP_DIR.parent / ".raofflineproxy-update.muxapp"
        )
        for module in (knulli, rocknix, darkos):
            self.assertEqual(module.PLATFORM.update_install_kind, "installer")

    def test_installer_platforms_run_the_downloaded_installer(self) -> None:
        from linux.raofflineproxy import update

        steps = []
        with patch.object(update, "download_update_installer", return_value=Path("/tmp/i.sh")):
            command = knulli.PLATFORM.install_update("https://x/k.sh", steps.append)
        self.assertEqual(command, ["/tmp/i.sh"])
        self.assertEqual(steps, ["Downloading update...", "Installing update..."])

    def test_archive_platforms_swap_their_app_dir_and_relaunch(self) -> None:
        from linux.raofflineproxy import update

        steps = []
        with patch.object(update, "download_update_archive", return_value=Path("/tmp/a.muxapp")) as download:
            with patch.object(update, "install_archive_update") as install:
                command = muos.PLATFORM.install_update("https://x/m.muxapp", steps.append)
        download.assert_called_once_with("https://x/m.muxapp", muos.PLATFORM.update_archive_path())
        install.assert_called_once_with(
            Path("/tmp/a.muxapp"), muos.APP_DIR, "", preserve_data=True, delete_archive=True
        )
        self.assertEqual(command, ["/bin/sh", str(muos.APP_DIR / "launch.sh"), "menu-sdl"])
        self.assertEqual(steps, ["Downloading update...", "Installing update..."])

    def test_menu_fonts(self) -> None:
        self.assertEqual(
            {m.PLATFORM.id: m.PLATFORM.menu_font for m in (onion, spruce, allium, muos, rocknix, knulli)},
            {
                "onion": "bundled",
                "spruce": "bundled",
                "allium": "bundled",
                "muos": "system",
                "rocknix": "system",
                "knulli": "default",
            },
        )

    def test_only_muos_switches_between_two_rom_roots(self) -> None:
        self.assertEqual(muos.PLATFORM.alternate_rom_root(), muos.SDCARD_ROMS_ROOT)
        for module in (allium, darkos, knulli, onion, rocknix, spruce, windows, generic):
            self.assertIsNone(module.PLATFORM.alternate_rom_root())

    def test_muos_brings_its_frontend_back(self) -> None:
        with patch.object(muos.subprocess, "Popen") as popen:
            muos.PLATFORM.restart_frontend()
        self.assertEqual(popen.call_args.args[0][:3], ["setsid", "-f", str(muos.FRONTEND_SCRIPT)])
        with patch.object(muos.subprocess, "Popen") as popen:
            generic.PLATFORM.restart_frontend()
        popen.assert_not_called()

    def test_only_windows_skips_controller_calibration(self) -> None:
        self.assertFalse(windows.PLATFORM.needs_controller_calibration)
        for module in (allium, darkos, knulli, muos, onion, rocknix, spruce, generic):
            self.assertTrue(module.PLATFORM.needs_controller_calibration)


class DisplayHookTests(unittest.TestCase):
    def test_miyoo_detect_panel_size_reads_the_probe_result(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            probe = Path(temp_dir) / "screen_resolution"
            probe.write_text("750x560\n", encoding="utf-8")
            with patch.object(miyoo, "SCREEN_RESOLUTION_FILE", probe):
                self.assertEqual(miyoo.detect_panel_size(), (750, 560))

    def test_miyoo_detect_panel_size_falls_back_to_the_default(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            probe = Path(temp_dir) / "screen_resolution"
            for content in (None, "garbage", "0x480"):
                if content is not None:
                    probe.write_text(content, encoding="utf-8")
                with patch.object(miyoo, "SCREEN_RESOLUTION_FILE", probe):
                    self.assertEqual(miyoo.detect_panel_size(), miyoo.DEFAULT_PANEL_SIZE)

    def test_open_mini_display_draws_through_a_renderer_texture(self) -> None:
        # The display code only runs on the device, so a name clash inside it once went
        # unnoticed until a real Onion menu failed to open. Run it against fakes.
        import sys
        import types

        class Surface:
            def __init__(self, size, depth=0):
                self.size = size

            def get_size(self):
                return self.size

        class Texture:
            def __init__(self, renderer, size, streaming=False):
                if size[0] > 640:
                    raise RuntimeError("texture too large")
                self.size = size
                self.updated = []

            def update(self, surface):
                self.updated.append(surface)

            def draw(self, dstrect):
                self.dstrect = dstrect

        class Renderer:
            def __init__(self, window, accelerated=-1, vsync=False):
                self.presented = 0

            def clear(self):
                pass

            def present(self):
                self.presented += 1

        video = types.ModuleType("pygame._sdl2.video")
        video.Renderer = Renderer
        video.Texture = Texture
        video.Window = types.SimpleNamespace(from_display_module=lambda: object())
        sdl2 = types.ModuleType("pygame._sdl2")
        sdl2.video = video
        pygame_module = types.ModuleType("pygame")
        pygame_module._sdl2 = sdl2
        pygame_module.Surface = Surface
        pygame_module.display = types.SimpleNamespace(set_mode=lambda size, flags: None, flip=None)
        pygame_module.transform = types.SimpleNamespace(
            scale=lambda source, size, target: None
        )

        for panel_size, expected_texture in (((640, 480), (640, 480)), ((750, 560), (640, 480))):
            logs = []
            with patch.dict(
                sys.modules,
                {"pygame": pygame_module, "pygame._sdl2": sdl2, "pygame._sdl2.video": video},
            ), patch.object(miyoo, "detect_panel_size", return_value=panel_size):
                surface = miyoo.open_mini_display(pygame_module, logs.append)
                self.assertEqual(surface.get_size(), panel_size)
                pygame_module.display.flip()
            self.assertTrue(logs)

    def test_only_the_mini_sdl_driver_takes_the_renderer_path(self) -> None:
        for module in (onion, spruce, allium):
            with self.subTest(platform=module.PLATFORM.id):
                with patch.object(miyoo, "running_on_mini_sdl_stack", return_value=False):
                    self.assertIsNone(module.PLATFORM.open_display(object(), print))
                    self.assertFalse(module.PLATFORM.reads_input_directly())
                with patch.object(miyoo, "running_on_mini_sdl_stack", return_value=True):
                    self.assertTrue(module.PLATFORM.reads_input_directly())
                    with patch.object(miyoo, "open_mini_display", return_value="surface"):
                        self.assertEqual(module.PLATFORM.open_display(object(), print), "surface")
        for module in (darkos, knulli, muos, rocknix, generic):
            self.assertIsNone(module.PLATFORM.open_display(object(), print))
            self.assertFalse(module.PLATFORM.reads_input_directly())

    def test_onion_explains_an_unsupported_build(self) -> None:
        with patch.object(onion.PLATFORM, "os_version", return_value="v4.3.1-1"):
            heading, message = onion.PLATFORM.unsupported_notice()
        self.assertEqual(heading, "Unsupported Onion version")
        self.assertIn("v4.3.1-1", message)
        self.assertIn(onion.SUPPORTED_VERSION_PREFIX, message)
        with patch.object(onion.PLATFORM, "os_version", return_value="v4.4.0-beta"):
            self.assertIsNone(onion.PLATFORM.unsupported_notice())
        self.assertIsNone(generic.PLATFORM.unsupported_notice())


class ServiceManagerTests(unittest.TestCase):
    def test_only_knulli_has_a_service_manager(self) -> None:
        for module in (allium, darkos, muos, onion, rocknix, spruce, windows, generic):
            self.assertIsNone(module.PLATFORM.service_manager())
        manager = knulli.PLATFORM.service_manager()
        self.assertEqual(manager.status_label, "KNULLI SERVICE")

    def test_the_manager_drives_the_services_tool(self) -> None:
        manager = knulli.PLATFORM.service_manager()
        with patch.object(knulli, "service_mode_active", return_value=True):
            self.assertTrue(manager.active())
        with patch.object(knulli, "enable_service_autostart") as enable:
            manager.set_autostart(True)
        enable.assert_called_once_with()
        with patch.object(knulli, "disable_service_autostart") as disable:
            manager.set_autostart(False)
        disable.assert_called_once_with()
        with patch.object(knulli, "start_service") as start, patch.object(knulli, "stop_service") as stop:
            manager.start()
            manager.stop()
        start.assert_called_once_with()
        stop.assert_called_once_with()


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
