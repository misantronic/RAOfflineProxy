import unittest
from unittest import mock

from linux.raofflineproxy import main


class ApplyProxyTests(unittest.TestCase):
    def test_apply_proxy_patches_and_starts_service(self) -> None:
        with (
            mock.patch.object(main, "remove_stale_hook"),
            mock.patch.object(
                main,
                "patch_retroarch_cfg",
                return_value={"already_patched": False, "changed": True},
            ),
            mock.patch.object(main, "enforce_patched_cfg"),
            mock.patch.object(
                main,
                "patch_batocera_conf",
                return_value={"previous": {}, "path": None, "exists": False},
            ),
            mock.patch.object(main, "load_patch_state", return_value={}),
            mock.patch.object(main, "save_patch_state"),
            mock.patch.object(
                main,
                "start_service_process",
                return_value={"already_running": False, "pid": 7},
            ) as start_service,
        ):
            output = main._apply_proxy({}, "/runtime/retroarch.cfg")

        start_service.assert_called_once()
        self.assertIn("Patched retroarch.cfg", output)
        self.assertIn("Service started (pid 7)", output)


class RevertProxyConfigTests(unittest.TestCase):
    def test_revert_without_state_uses_presence_strip(self) -> None:
        with (
            mock.patch.object(main, "remove_stale_hook"),
            mock.patch.object(main, "load_patch_state", return_value={}),
            mock.patch.object(main, "revert_batocera_conf", return_value={"exists": False}),
            mock.patch.object(
                main, "revert_retroarch_cfg", return_value={"changed": True}
            ) as revert_cfg,
            mock.patch.object(main, "start_service_process") as start_service,
        ):
            output = main._revert_proxy_config({}, "/runtime/retroarch.cfg")

        revert_cfg.assert_called_once_with("/runtime/retroarch.cfg")
        start_service.assert_not_called()
        self.assertEqual(output[-1], "Reverted retroarch.cfg")

    def test_revert_prefers_saved_state(self) -> None:
        state = {"cfg_path": "/saved/retroarch.cfg", "batocera_previous": {}}
        with (
            mock.patch.object(main, "remove_stale_hook"),
            mock.patch.object(main, "load_patch_state", return_value=state),
            mock.patch.object(main, "revert_batocera_conf", return_value={"exists": False}),
            mock.patch.object(
                main, "revert_retroarch_cfg", return_value={"changed": True}
            ) as revert_cfg,
        ):
            main._revert_proxy_config({}, "/runtime/retroarch.cfg")

        revert_cfg.assert_called_once_with("/saved/retroarch.cfg", state)


class BootReconcileDispatchTests(unittest.TestCase):
    def _run(self, argv):
        with (
            mock.patch.object(main.sys, "argv", ["raofflineproxy", *argv]),
            mock.patch.object(main, "configure_logging"),
            mock.patch.object(main, "load_config", return_value={}),
            mock.patch.object(main, "detect_retroarch_cfg", return_value="/runtime/retroarch.cfg"),
            mock.patch.object(main, "_apply_proxy", return_value=[]) as apply_proxy,
            mock.patch.object(main, "_revert_proxy_config", return_value=[]) as revert_config,
            mock.patch.object(main, "is_autostart_enabled") as autostart,
        ):
            autostart.return_value = argv == ["boot-reconcile"] and self._autostart_on
            main.main()
        return apply_proxy, revert_config

    def test_boot_reconcile_on_applies_proxy(self) -> None:
        self._autostart_on = True
        apply_proxy, revert_config = self._run(["boot-reconcile"])
        apply_proxy.assert_called_once()
        revert_config.assert_not_called()

    def test_boot_reconcile_off_reverts_config(self) -> None:
        self._autostart_on = False
        apply_proxy, revert_config = self._run(["boot-reconcile"])
        revert_config.assert_called_once()
        apply_proxy.assert_not_called()


if __name__ == "__main__":
    unittest.main()
