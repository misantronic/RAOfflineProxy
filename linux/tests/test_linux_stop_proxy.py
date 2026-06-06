import unittest
from unittest import mock

from linux.raofflineproxy import main
from linux.raofflineproxy import menu_sdl


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
        )
