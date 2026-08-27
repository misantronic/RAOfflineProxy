import unittest
from unittest import mock

from linux.raofflineproxy import main
from linux.raofflineproxy import menu_sdl


class LinuxStartProxyInlineTests(unittest.TestCase):
    def _start(self, patch_result: dict) -> bool:
        with (
            mock.patch.object(
                menu_sdl, "runtime_config", return_value=({}, "/runtime/retroarch.cfg")
            ),
            mock.patch.object(menu_sdl, "remove_stale_hook"),
            mock.patch.object(
                menu_sdl, "patch_retroarch_cfg", return_value=patch_result
            ),
            mock.patch.object(menu_sdl, "enforce_patched_cfg"),
            mock.patch.object(menu_sdl, "patch_batocera_conf", return_value={}),
            mock.patch.object(menu_sdl, "patch_ppsspp_ini", return_value={}),
            mock.patch.object(menu_sdl, "patch_dolphin_ini", return_value={}),
            mock.patch.object(menu_sdl, "load_patch_state", return_value={}),
            mock.patch.object(menu_sdl, "store_batocera_previous"),
            mock.patch.object(menu_sdl, "store_ppsspp_previous"),
            mock.patch.object(menu_sdl, "store_dolphin_previous"),
            mock.patch.object(menu_sdl, "save_patch_state"),
            mock.patch.object(menu_sdl, "start_service_process"),
        ):
            return menu_sdl.start_proxy_inline()

    def test_reports_a_skipped_cfg_so_the_menu_can_say_so(self) -> None:
        self.assertTrue(self._start({"exists": False, "changed": False}))

    def test_reports_nothing_when_the_cfg_was_patched(self) -> None:
        self.assertFalse(self._start({"exists": True, "changed": True}))


class LinuxStopProxyTests(unittest.TestCase):
    def test_safe_stop_proxy_prefers_saved_patch_cfg_path(self) -> None:
        with (
            mock.patch.object(main, "remove_stale_hook"),
            mock.patch.object(main, "stop_service_process", return_value={"pid": 1}),
            mock.patch.object(
                main,
                "load_patch_state",
                return_value={"cfg_path": "/saved/retroarch.cfg", "batocera_previous": {}},
            ),
            mock.patch.object(main, "revert_batocera_conf", return_value={"exists": False}),
            mock.patch.object(
                main,
                "revert_retroarch_cfg",
                return_value={"changed": True},
            ) as revert_cfg,
        ):
            output = main.safe_stop_proxy({}, "/runtime/retroarch.cfg")

        revert_cfg.assert_called_once_with(
            "/saved/retroarch.cfg",
            {"cfg_path": "/saved/retroarch.cfg", "batocera_previous": {}},
            config_data={},
        )
        self.assertEqual(output[-1], "Reverted retroarch.cfg")

    def test_stop_proxy_inline_prefers_saved_patch_cfg_path(self) -> None:
        with (
            mock.patch.object(menu_sdl, "runtime_config", return_value=({}, "/runtime/retroarch.cfg")),
            mock.patch.object(menu_sdl, "remove_stale_hook"),
            mock.patch.object(menu_sdl, "stop_service_process", return_value={"pid": 1}),
            mock.patch.object(
                menu_sdl,
                "load_patch_state",
                return_value={"cfg_path": "/saved/retroarch.cfg", "batocera_previous": {}},
            ),
            mock.patch.object(menu_sdl, "revert_batocera_conf"),
            mock.patch.object(menu_sdl, "revert_retroarch_cfg") as revert_cfg,
        ):
            menu_sdl.stop_proxy_inline()

        revert_cfg.assert_called_once_with(
            "/saved/retroarch.cfg",
            {"cfg_path": "/saved/retroarch.cfg", "batocera_previous": {}},
            config_data={},
        )
