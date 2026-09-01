import os
import tempfile
import zipfile
import unittest
from contextlib import ExitStack
from pathlib import Path
from unittest.mock import patch

from linux.raofflineproxy import config, log_uploader, menu_sdl, platform, update


class AlliumDetectionTests(unittest.TestCase):
    def test_running_on_allium_requires_the_marker_dir(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            marker = Path(temp_dir) / ".allium"
            with patch.object(config, "ALLIUM_MARKER_DIR", marker):
                self.assertFalse(config.running_on_allium())
                marker.mkdir()
                self.assertTrue(config.running_on_allium())

    def test_running_on_shared_miyoo_stack_includes_allium(self) -> None:
        with patch.object(config, "running_on_allium", return_value=True):
            with patch.object(config, "running_on_onion", return_value=False):
                with patch.object(config, "running_on_spruce", return_value=False):
                    self.assertTrue(config.running_on_shared_miyoo_stack())

    def test_resolve_config_dir_uses_allium_pak_data_dir(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            app_dir = Path(temp_dir) / "RAOfflineProxy.pak"
            app_dir.mkdir()
            # Both env vars are checked before any platform path, so leaving them to the
            # ambient environment makes this pass or fail depending on the machine: CI
            # sets XDG_CONFIG_HOME and returned from it long before the Allium branch.
            with patch.dict(os.environ):
                os.environ.pop("RAOFFLINEPROXY_CONFIG_DIR", None)
                os.environ.pop("XDG_CONFIG_HOME", None)
                with patch.object(config, "DEFAULT_ONION_APP_DIR", Path(temp_dir) / "absent"):
                    with patch.object(config, "DEFAULT_ALLIUM_APP_DIR", app_dir):
                        self.assertEqual(config.resolve_config_dir(), app_dir / "data")


# Verbatim copy of Allium v1.0.1's /mnt/SDCARD/.tmp_update/updater, pulled off a real Miyoo
# Mini running Allium. The hook is inserted into this exact file at boot, so the tests run
# against the real thing rather than a hand-written approximation. It is the same boot
# entry point spruce uses on this hardware (execs its own long-running UI process and never
# returns), so the hook is prepended after the shebang rather than appended.
ALLIUM_UPDATER = """#!/bin/sh
export ROOT="/mnt/SDCARD"
export LD_LIBRARY_PATH="/lib:/config/lib:/customer/lib:$ROOT/miyoo:$ROOT/.tmp_update/lib"
export PATH="$ROOT/.tmp_update/bin:$PATH"

# init backlight
echo 0 >/sys/class/pwm/pwmchip0/export
echo 800 >/sys/class/pwm/pwmchip0/pwm0/period
echo 6 >/sys/class/pwm/pwmchip0/pwm0/duty_cycle
echo 1 >/sys/class/pwm/pwmchip0/pwm0/enable

# restore timezone
if [ -f $ROOT/.allium/state/timezone ]; then
    export TZ
    TZ="$(cat $ROOT/.allium/state/timezone)"
fi

# check for OTA update
if [ -f "$ROOT/allium-ota.zip" ]; then
    "$ROOT/.allium/scripts/ota-update.sh"
fi

# run migration scripts that haven't been run yet
for script in "$ROOT"/.allium/migrations/*; do
    if [ ! -f "$script/.done" ]; then
        if ! "$script/run.sh" >"$script/run.log"; then
            reboot
            sleep 10
        fi
        touch "$script/.done"
    fi
done

# run Allium
RUST_LOG=info "$ROOT"/.allium/bin/alliumd >"$ROOT"/allium.log 2>&1

while true; do
    reboot
    sleep 10
done
"""


class AlliumBootHookTests(unittest.TestCase):
    def _updater(self):
        temp_dir = tempfile.TemporaryDirectory()
        self.addCleanup(temp_dir.cleanup)
        path = Path(temp_dir.name) / "updater"
        path.write_text(ALLIUM_UPDATER, encoding="utf-8")
        return path

    def test_hook_is_inserted_above_the_dispatch_not_appended(self) -> None:
        path = self._updater()
        platform.install_allium_boot_hook(path)
        content = path.read_text(encoding="utf-8")

        hook_at = content.index(platform.AUTOSTART_SENTINEL_START)
        self.assertTrue(content.startswith("#!"))
        self.assertLess(hook_at, content.index("alliumd"))
        self.assertIn(str(platform.ALLIUM_AUTOSTART_LAUNCHER), content)
        self.assertIn("&", content[hook_at : content.index("alliumd")])

    def test_original_boot_logic_is_preserved(self) -> None:
        path = self._updater()
        platform.install_allium_boot_hook(path)
        content = path.read_text(encoding="utf-8")

        for line in ALLIUM_UPDATER.splitlines():
            if line.strip():
                self.assertIn(line, content)

    def test_hook_skips_autostart_when_an_ota_update_is_pending(self) -> None:
        # The hook is prepended above the updater's own OTA check, and ota-update.sh
        # extracts the release over the whole card and then reboots. Starting the service
        # into that would open its SQLite database mid-extract, seconds before the reboot.
        path = self._updater()
        platform.install_allium_boot_hook(path)
        content = path.read_text(encoding="utf-8")

        block = content[
            content.index(platform.AUTOSTART_SENTINEL_START) : content.index(
                platform.AUTOSTART_SENTINEL_END
            )
        ]
        self.assertIn(f'[ ! -f "{platform.ALLIUM_OTA_ARCHIVE}" ]', block)
        # Guard must sit above the updater's own check, or it would never be reached.
        self.assertLess(
            content.index(platform.AUTOSTART_SENTINEL_END), content.index("ota-update.sh")
        )

    def test_ota_guard_matches_the_path_allium_itself_checks(self) -> None:
        # Verbatim from the real updater: a drifted path would silently disable the guard.
        self.assertIn('if [ -f "$ROOT/allium-ota.zip" ]', ALLIUM_UPDATER)
        self.assertEqual(
            str(platform.ALLIUM_OTA_ARCHIVE), "/mnt/SDCARD/allium-ota.zip"
        )

    def test_install_is_idempotent(self) -> None:
        path = self._updater()
        platform.install_allium_boot_hook(path)
        once = path.read_text(encoding="utf-8")
        platform.install_allium_boot_hook(path)
        platform.install_allium_boot_hook(path)
        self.assertEqual(path.read_text(encoding="utf-8"), once)
        self.assertEqual(once.count(platform.AUTOSTART_SENTINEL_START), 1)

    def test_remove_strips_the_block_without_deleting_the_updater(self) -> None:
        path = self._updater()
        platform.install_allium_boot_hook(path)
        with patch.object(platform, "running_on_allium", return_value=True):
            with patch.object(platform, "DEFAULT_ALLIUM_STARTUP_SCRIPT", path):
                platform.remove_boot_hook({"startup_script": str(path)})

        content = path.read_text(encoding="utf-8")
        self.assertTrue(path.exists())
        self.assertNotIn(platform.AUTOSTART_SENTINEL_START, content)
        self.assertIn("alliumd", content)

    def test_unrecognised_boot_file_is_refused(self) -> None:
        temp_dir = tempfile.TemporaryDirectory()
        self.addCleanup(temp_dir.cleanup)
        path = Path(temp_dir.name) / "updater"
        path.write_text("not a shell script at all\n", encoding="utf-8")

        with self.assertRaises(ValueError):
            platform.install_allium_boot_hook(path)
        self.assertNotIn(platform.AUTOSTART_SENTINEL_START, path.read_text(encoding="utf-8"))

    def test_autostart_is_supported_on_allium(self) -> None:
        with patch.object(platform, "running_on_allium", return_value=True):
            with patch.object(platform, "running_on_spruce", return_value=False):
                self.assertEqual(
                    platform.resolve_startup_script_path({}),
                    platform.DEFAULT_ALLIUM_STARTUP_SCRIPT,
                )
                self.assertTrue(platform.autostart_supported({}))
                self.assertEqual(
                    platform.autostart_command({}),
                    (str(platform.ALLIUM_AUTOSTART_LAUNCHER),),
                )

    def test_allium_branch_does_not_fire_on_spruce(self) -> None:
        # DEFAULT_ALLIUM_STARTUP_SCRIPT and DEFAULT_SPRUCE_STARTUP_SCRIPT are literally the
        # same path on real hardware (.tmp_update/updater), so the dispatch must key off
        # running_on_allium() rather than path equality alone.
        with patch.object(platform, "running_on_allium", return_value=False):
            with patch.object(platform, "running_on_spruce", return_value=True):
                self.assertEqual(
                    platform.autostart_command({}),
                    (str(platform.SPRUCE_AUTOSTART_LAUNCHER),),
                )


class AlliumUpdateSafetyTests(unittest.TestCase):
    def _session(self):
        return menu_sdl.MenuSdlSession.__new__(menu_sdl.MenuSdlSession)

    def test_update_platform_is_allium_not_knulli(self) -> None:
        # A "knulli" fallback here would make install_update()'s generic branch download
        # and exec a Knulli installer binary on Miyoo Mini hardware.
        session = self._session()
        with patch.object(menu_sdl, "running_on_muos", return_value=False):
            with patch.object(menu_sdl, "running_on_spruce", return_value=False):
                with patch.object(menu_sdl, "running_on_onion", return_value=False):
                    with patch.object(menu_sdl, "running_on_allium", return_value=True):
                        self.assertEqual(session.update_platform(), "allium")

    def test_allium_is_an_installable_update_platform(self) -> None:
        self.assertEqual(update.validate_platform("Allium"), update.PLATFORM_ALLIUM)

    def test_asset_lookup_does_not_cross_match_allium_and_onion(self) -> None:
        assets = [
            {
                "name": "RAOfflineProxy-Onion-v1.11.1-alpha1.zip",
                "browser_download_url": "https://example.test/onion.zip",
            },
            {
                "name": "RAOfflineProxy-Allium-v1.11.1-alpha1.zip",
                "browser_download_url": "https://example.test/allium.zip",
            },
        ]
        self.assertEqual(
            update.find_platform_asset_url(update.PLATFORM_ALLIUM, assets),
            "https://example.test/allium.zip",
        )
        self.assertEqual(
            update.find_platform_asset_url(update.PLATFORM_ONION, assets),
            "https://example.test/onion.zip",
        )

    def test_install_update_uses_the_pak_layout_not_the_onion_one(self) -> None:
        session = self._session()
        with ExitStack() as stack:
            stack.enter_context(patch.object(menu_sdl, "running_on_muos", return_value=False))
            stack.enter_context(patch.object(menu_sdl, "running_on_allium", return_value=True))
            install = stack.enter_context(
                patch.object(menu_sdl.MenuSdlSession, "install_update_archive")
            )
            session.install_update()

        install.assert_called_once_with(config.DEFAULT_ALLIUM_APP_DIR, "Apps")

    def test_onion_and_spruce_still_get_the_app_layout(self) -> None:
        # The archive_root parameter was added for Allium; these two must keep the layout
        # they already ship, or the refactor silently breaks their updates instead.
        for onion, spruce in ((True, False), (False, True)):
            with self.subTest(onion=onion, spruce=spruce):
                session = self._session()
                with ExitStack() as stack:
                    stack.enter_context(
                        patch.object(menu_sdl, "running_on_muos", return_value=False)
                    )
                    stack.enter_context(
                        patch.object(menu_sdl, "running_on_allium", return_value=False)
                    )
                    stack.enter_context(
                        patch.object(menu_sdl, "running_on_onion", return_value=onion)
                    )
                    stack.enter_context(
                        patch.object(menu_sdl, "running_on_spruce", return_value=spruce)
                    )
                    install = stack.enter_context(
                        patch.object(menu_sdl.MenuSdlSession, "install_update_archive")
                    )
                    session.install_update()

                install.assert_called_once_with(config.DEFAULT_ONION_APP_DIR, "App")

    def test_no_update_channel_is_silent_not_a_logged_failure(self) -> None:
        # Signalling "no update channel" by returning a name validate_platform() rejects
        # logged an "update check failed" line on every menu refresh — 17 of them in one
        # real support upload, drowning genuine errors in the diagnostics log.
        session = self._session()
        session.main_service_mode = False
        session.config_data = {}
        session.view = "main"
        session.main_update_dialog_seen = True

        logged: list[str] = []
        with ExitStack() as stack:
            stack.enter_context(patch.object(menu_sdl, "log_menu_sdl", logged.append))
            stack.enter_context(patch.object(menu_sdl, "load_config", return_value={}))
            stack.enter_context(
                patch.object(menu_sdl, "service_mode_active", return_value=False)
            )
            stack.enter_context(
                patch.object(menu_sdl, "online_check", return_value=False)
            )
            stack.enter_context(
                patch.object(menu_sdl, "autostart_supported", return_value=False)
            )
            stack.enter_context(
                patch.object(
                    menu_sdl.MenuSdlSession, "read_proxy_running", return_value=False
                )
            )
            stack.enter_context(
                patch.object(
                    menu_sdl.MenuSdlSession, "is_logged_in", return_value=False
                )
            )
            stack.enter_context(
                patch.object(
                    menu_sdl.MenuSdlSession, "update_platform", return_value=None
                )
            )
            update_status = stack.enter_context(
                patch.object(menu_sdl, "update_status")
            )
            menu_sdl.MenuSdlSession.refresh_main_menu_state(session, force=True)

        update_status.assert_not_called()
        self.assertFalse(session.main_update_available)
        self.assertEqual([line for line in logged if "update check failed" in line], [])

    def test_resolve_update_asset_url_is_none_without_a_channel(self) -> None:
        session = self._session()
        session.main_update_asset_url = None
        with patch.object(menu_sdl.MenuSdlSession, "update_platform", return_value=None):
            with patch.object(menu_sdl, "update_status") as update_status:
                self.assertIsNone(session.resolve_update_asset_url())
        update_status.assert_not_called()


class AlliumUpdateInstallTests(unittest.TestCase):
    """The .pak layout driven through the real installer, not just asserted as a string.

    This is the flow that decides whether an Allium release is upgradeable at all, so it
    runs against an archive shaped exactly like the one build_bundle.sh produces.
    """

    def _archive(self, root: Path, version: str = "v1.13.0-alpha1") -> Path:
        archive_path = root / "update.zip"
        pak = "Apps/RAOfflineProxy.pak"
        with zipfile.ZipFile(archive_path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            archive.writestr(f"{pak}/new.txt", "new")
            archive.writestr(f"{pak}/common.sh", f"APP_VERSION={version}\n")
            archive.writestr(f"{pak}/data/proxy.sqlite3", "fresh-db")
            launch_info = zipfile.ZipInfo(f"{pak}/launch.sh")
            launch_info.external_attr = 0o755 << 16
            archive.writestr(launch_info, "#!/bin/sh\nexit 0\n")
        return archive_path

    def _installed_pak(self, root: Path) -> Path:
        app_dir = root / "RAOfflineProxy.pak"
        (app_dir / "data").mkdir(parents=True)
        (app_dir / "old.txt").write_text("old", encoding="utf-8")
        (app_dir / "data" / "proxy.sqlite3").write_text("cached-db", encoding="utf-8")
        return app_dir

    def _install(self, archive_path: Path, app_dir: Path, current: str) -> None:
        with patch.object(update, "current_version", lambda: current):
            update.install_onion_update_archive(archive_path, app_dir, "Apps")

    def test_replaces_the_pak_and_preserves_the_data_dir(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            app_dir = self._installed_pak(root)
            self._install(self._archive(root), app_dir, "1.11.1-alpha1")

            self.assertFalse((app_dir / "old.txt").exists())
            self.assertEqual((app_dir / "new.txt").read_text(encoding="utf-8"), "new")
            # The queued-award database must survive a same-major update.
            self.assertEqual(
                (app_dir / "data" / "proxy.sqlite3").read_text(encoding="utf-8"), "cached-db"
            )
            self.assertEqual((app_dir / "launch.sh").stat().st_mode & 0o777, 0o755)

    def test_a_wrong_archive_root_leaves_the_install_untouched(self) -> None:
        # Guards the parameter itself: passing Onion's "App" against a .pak archive must
        # fail before anything is swapped, not half-replace the app directory.
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            app_dir = self._installed_pak(root)

            with self.assertRaises(RuntimeError):
                with patch.object(update, "current_version", lambda: "1.11.1-alpha1"):
                    update.install_onion_update_archive(self._archive(root), app_dir, "App")

            self.assertEqual((app_dir / "old.txt").read_text(encoding="utf-8"), "old")
            self.assertEqual(
                (app_dir / "data" / "proxy.sqlite3").read_text(encoding="utf-8"), "cached-db"
            )

    def test_the_built_bundle_matches_the_layout_the_installer_expects(self) -> None:
        # Ties the installer to build_bundle.sh: if the bundle's in-archive path ever
        # changes, the update flow breaks on device and nothing else would catch it.
        build_bundle = (
            Path(__file__).resolve().parents[1] / "allium" / "build_bundle.sh"
        ).read_text(encoding="utf-8")
        self.assertIn('APP_DIR="${BUILD_DIR}/Apps/RAOfflineProxy.pak"', build_bundle)
        self.assertIn("RAOfflineProxy-Allium-v${APP_VERSION}.zip", build_bundle)
        self.assertEqual(
            str(config.DEFAULT_ALLIUM_APP_DIR), "/mnt/SDCARD/Apps/RAOfflineProxy.pak"
        )


class UpdateCleanupRetryTests(unittest.TestCase):
    """Measured on a Miyoo Mini: the first rmtree after an install writes ~129MB bails
    partway on FAT32 write-back, leaving 318 files, while an immediate retry removes all of
    them with zero errors. ignore_errors=True made that invisible."""

    def test_a_transient_first_failure_is_retried(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            target = Path(temp_dir) / "leftover"
            (target / "nested").mkdir(parents=True)
            (target / "nested" / "file.txt").write_text("x", encoding="utf-8")

            real_rmtree = update.shutil.rmtree
            calls = []

            def flaky(path, ignore_errors=False, **kwargs):
                calls.append(path)
                if len(calls) == 1:
                    return  # first pass silently leaves the tree behind
                return real_rmtree(path, ignore_errors=ignore_errors, **kwargs)

            with patch.object(update.shutil, "rmtree", flaky):
                with patch.object(update.time, "sleep"):
                    update.remove_tree_best_effort(target)

            self.assertFalse(target.exists())
            self.assertEqual(len(calls), 2)

    def test_a_persistent_failure_warns_instead_of_silently_leaving_debris(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            target = Path(temp_dir) / "leftover"
            target.mkdir()

            with patch.object(update.shutil, "rmtree", lambda *a, **k: None):
                with patch.object(update.time, "sleep"):
                    with self.assertLogs(update.LOGGER, level="WARNING") as logged:
                        update.remove_tree_best_effort(target)

            self.assertTrue(any("Could not fully remove" in m for m in logged.output))

    def test_an_already_absent_tree_does_no_work(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            with patch.object(update.shutil, "rmtree") as rmtree:
                update.remove_tree_best_effort(Path(temp_dir) / "absent")
            rmtree.assert_not_called()


class AlliumLogMetadataTests(unittest.TestCase):
    def test_platform_label_and_version_use_allium_file(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            version_file = Path(temp_dir) / "version.txt"
            version_file.write_text("v1.0.1\n", encoding="utf-8")
            with patch.object(log_uploader, "ALLIUM_VERSION_FILE", version_file):
                with patch.object(config, "running_on_spruce", return_value=False):
                    with patch.object(config, "running_on_onion", return_value=False):
                        with patch.object(config, "running_on_allium", return_value=True):
                            self.assertEqual(log_uploader._platform_label(), "Allium")
                            self.assertEqual(
                                log_uploader._os_version_value("Allium"), "v1.0.1"
                            )

    def test_device_label_does_not_fall_back_to_the_string_onion(self) -> None:
        # /tmp/deviceModel is written by Onion's own runtime and does not exist on Allium
        # (verified on a Miyoo Mini Plus), so routing Allium through _onion_device_label()
        # would label every Allium device "Onion" in support logs.
        with tempfile.TemporaryDirectory() as temp_dir:
            missing = Path(temp_dir) / "deviceModel"
            model_file = Path(temp_dir) / "model"
            model_file.write_text("INFINITY2M SSC011A-S01A-S\0", encoding="utf-8")
            with patch.object(log_uploader, "ONION_DEVICE_MODEL_FILE", missing):
                with patch.object(log_uploader, "DEVICETREE_MODEL_FILE", model_file):
                    self.assertEqual(
                        log_uploader._device_label("Allium"), "INFINITY2M SSC011A-S01A-S"
                    )
                    self.assertEqual(log_uploader._device_label("Onion"), "Onion")


if __name__ == "__main__":
    unittest.main()
