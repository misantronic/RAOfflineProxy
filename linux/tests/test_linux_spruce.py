import json
import os
import socket
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from linux.raofflineproxy import (
    config,
    log_uploader,
    platform,
    retroarch_cfg,
    service,
    spruce_conf,
    update,
)


class SpruceDetectionTests(unittest.TestCase):
    def test_running_on_onion_is_false_when_spruce_marker_present(self) -> None:
        with patch.object(config, "running_on_spruce", return_value=True):
            with patch.object(Path, "exists", return_value=True):
                self.assertFalse(config.running_on_onion())
                self.assertTrue(config.running_on_onion_or_spruce())

    def test_running_on_onion_still_true_without_spruce_marker(self) -> None:
        with patch.object(config, "running_on_spruce", return_value=False):
            with patch.object(Path, "exists", return_value=True):
                self.assertTrue(config.running_on_onion())
                self.assertTrue(config.running_on_onion_or_spruce())

    def test_spruce_platform_reads_cpuinfo_tokens(self) -> None:
        cases = {
            "Hardware\t: sun8i\n": "A30",
            "Hardware\t: TG5040\n": "SmartPro",
            "CPU part\t: 0xd05\n": "Flip",
            "CPU part\t: 0xd03\n": "AnbernicRG_XX-universal",
        }
        for cpuinfo, expected in cases.items():
            with tempfile.TemporaryDirectory() as temp_dir:
                cpuinfo_path = Path(temp_dir) / "cpuinfo"
                cpuinfo_path.write_text(cpuinfo, encoding="utf-8")
                with patch.object(config, "CPUINFO_PATH", cpuinfo_path):
                    self.assertEqual(config.spruce_platform(), expected)

    def test_spruce_platform_defaults_to_miyoo_mini(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            cpuinfo_path = Path(temp_dir) / "cpuinfo"
            cpuinfo_path.write_text("Hardware\t: unknown\n", encoding="utf-8")
            with patch.object(config, "CPUINFO_PATH", cpuinfo_path):
                with patch.object(config, "MAGICX_MARKER", Path(temp_dir) / "absent"):
                    self.assertEqual(config.spruce_platform(), "MiyooMini")

    def test_spruce_retroarch_cfg_points_at_platform_file(self) -> None:
        with patch.object(config, "spruce_platform", return_value="MiyooMini"):
            self.assertEqual(
                config.spruce_retroarch_cfg(),
                Path("/mnt/SDCARD/RetroArch/platform/retroarch-MiyooMini.cfg"),
            )

    def test_detect_retroarch_cfg_prefers_spruce_platform_file(self) -> None:
        original = os.environ.pop("RAOFFLINEPROXY_RETROARCH_CFG", None)
        try:
            with patch.object(config, "running_on_spruce", return_value=True):
                with patch.object(config, "spruce_platform", return_value="A30"):
                    self.assertEqual(
                        config.detect_retroarch_cfg(),
                        "/mnt/SDCARD/RetroArch/platform/retroarch-A30.cfg",
                    )
        finally:
            if original is not None:
                os.environ["RAOFFLINEPROXY_RETROARCH_CFG"] = original


class SpruceCredentialTests(unittest.TestCase):
    SETTINGS = {
        "menuOptions": {
            "RetroAchievements Settings": {
                "modeToggle": {"selected": "Softcore"},
                "username": {"selected": "markadia"},
                "password": {"selected": "hunter2"},
            }
        }
    }

    def _with_settings(self, payload):
        temp_dir = tempfile.TemporaryDirectory()
        self.addCleanup(temp_dir.cleanup)
        settings = Path(temp_dir.name) / "spruce-config.json"
        settings.write_text(json.dumps(payload), encoding="utf-8")
        return patch.object(config, "SPRUCE_CONFIG_JSON", settings)

    def test_reads_credentials_from_spruce_settings(self) -> None:
        with self._with_settings(self.SETTINGS):
            with patch.object(retroarch_cfg, "running_on_spruce", return_value=True):
                self.assertEqual(
                    retroarch_cfg.load_spruce_credentials(),
                    {"user": "markadia", "password": "hunter2"},
                )

    def test_ignored_when_not_on_spruce(self) -> None:
        with self._with_settings(self.SETTINGS):
            with patch.object(retroarch_cfg, "running_on_spruce", return_value=False):
                self.assertIsNone(retroarch_cfg.load_spruce_credentials())

    def test_blank_credentials_are_not_returned(self) -> None:
        payload = {
            "menuOptions": {
                "RetroAchievements Settings": {
                    "username": {"selected": ""},
                    "password": {"selected": "hunter2"},
                }
            }
        }
        with self._with_settings(payload):
            with patch.object(retroarch_cfg, "running_on_spruce", return_value=True):
                self.assertIsNone(retroarch_cfg.load_spruce_credentials())

    def test_missing_or_corrupt_settings_file_is_tolerated(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            missing = Path(temp_dir) / "absent.json"
            with patch.object(config, "SPRUCE_CONFIG_JSON", missing):
                self.assertIsNone(config.spruce_setting("username"))

            corrupt = Path(temp_dir) / "corrupt.json"
            corrupt.write_text("{not json", encoding="utf-8")
            with patch.object(config, "SPRUCE_CONFIG_JSON", corrupt):
                self.assertIsNone(config.spruce_setting("username"))

    def test_cfg_credentials_still_win_over_spruce_settings(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            cfg = Path(temp_dir) / "retroarch-MiyooMini.cfg"
            cfg.write_text(
                'cheevos_username = "fromcfg"\ncheevos_token = "tok"\n', encoding="utf-8"
            )
            with self._with_settings(self.SETTINGS):
                with patch.object(retroarch_cfg, "running_on_spruce", return_value=True):
                    self.assertEqual(
                        retroarch_cfg.load_retroarch_credentials(str(cfg)),
                        {"user": "fromcfg", "token": "tok"},
                    )

    def test_spruce_settings_used_when_cfg_has_no_credentials(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            # What the device actually looks like before the first game launch: spruce
            # has not yet copied its credentials into the RetroArch config.
            cfg = Path(temp_dir) / "retroarch-MiyooMini.cfg"
            cfg.write_text(
                'cheevos_username = ""\ncheevos_password = ""\n', encoding="utf-8"
            )
            with self._with_settings(self.SETTINGS):
                with patch.object(retroarch_cfg, "running_on_spruce", return_value=True):
                    self.assertEqual(
                        retroarch_cfg.load_retroarch_credentials(str(cfg)),
                        {"user": "markadia", "password": "hunter2"},
                    )


class SpruceModeTests(unittest.TestCase):
    def _settings(self, mode):
        temp_dir = tempfile.TemporaryDirectory()
        self.addCleanup(temp_dir.cleanup)
        path = Path(temp_dir.name) / "spruce-config.json"
        payload = {
            "menuOptions": {
                "RetroAchievements Settings": {
                    "modeToggle": {
                        "display": "Retroachivements mode",
                        "options": ["Manual", "Disabled", "Softcore", "Hardcore"],
                        "selected": mode,
                    },
                    "username": {"selected": "someone"},
                }
            }
        }
        path.write_text(json.dumps(payload, indent=4), encoding="utf-8")
        return path

    def _read_mode(self, path):
        data = json.loads(path.read_text(encoding="utf-8"))
        return data["menuOptions"]["RetroAchievements Settings"]["modeToggle"]["selected"]

    def test_disabled_is_switched_to_softcore(self) -> None:
        # spruce rewrites cheevos_enable from this setting on every game launch, after our
        # own patch, so leaving it on Disabled silently turns achievements off.
        path = self._settings("Disabled")
        with patch.object(spruce_conf, "running_on_spruce", return_value=True):
            with patch.object(config, "SPRUCE_CONFIG_JSON", path):
                result = spruce_conf.patch_spruce_mode({})
        self.assertTrue(result["changed"])
        self.assertEqual(result["previous"], "Disabled")
        self.assertEqual(self._read_mode(path), "Softcore")

    def test_hardcore_is_switched_to_softcore(self) -> None:
        path = self._settings("Hardcore")
        with patch.object(spruce_conf, "running_on_spruce", return_value=True):
            with patch.object(config, "SPRUCE_CONFIG_JSON", path):
                spruce_conf.patch_spruce_mode({})
        self.assertEqual(self._read_mode(path), "Softcore")

    def test_softcore_is_left_alone(self) -> None:
        path = self._settings("Softcore")
        before = path.read_text(encoding="utf-8")
        with patch.object(spruce_conf, "running_on_spruce", return_value=True):
            with patch.object(config, "SPRUCE_CONFIG_JSON", path):
                result = spruce_conf.patch_spruce_mode({})
        self.assertFalse(result["changed"])
        self.assertTrue(result["already_patched"])
        self.assertEqual(path.read_text(encoding="utf-8"), before)

    def test_other_settings_are_preserved(self) -> None:
        path = self._settings("Disabled")
        with patch.object(spruce_conf, "running_on_spruce", return_value=True):
            with patch.object(config, "SPRUCE_CONFIG_JSON", path):
                spruce_conf.patch_spruce_mode({})
        data = json.loads(path.read_text(encoding="utf-8"))
        section = data["menuOptions"]["RetroAchievements Settings"]
        self.assertEqual(section["username"]["selected"], "someone")
        self.assertEqual(
            section["modeToggle"]["options"], ["Manual", "Disabled", "Softcore", "Hardcore"]
        )

    def test_revert_restores_the_users_mode(self) -> None:
        path = self._settings("Disabled")
        with patch.object(spruce_conf, "running_on_spruce", return_value=True):
            with patch.object(config, "SPRUCE_CONFIG_JSON", path):
                result = spruce_conf.patch_spruce_mode({})
                state = {}
                spruce_conf.store_spruce_previous(state, result)
                spruce_conf.revert_spruce_mode({}, state["spruce_previous_mode"])
        self.assertEqual(self._read_mode(path), "Disabled")

    def test_repatching_does_not_poison_the_saved_mode(self) -> None:
        path = self._settings("Disabled")
        state = {}
        with patch.object(spruce_conf, "running_on_spruce", return_value=True):
            with patch.object(config, "SPRUCE_CONFIG_JSON", path):
                spruce_conf.store_spruce_previous(state, spruce_conf.patch_spruce_mode({}))
                # second start while already patched must not record "Softcore"
                spruce_conf.store_spruce_previous(state, spruce_conf.patch_spruce_mode({}))
                spruce_conf.revert_spruce_mode({}, state["spruce_previous_mode"])
        self.assertEqual(state["spruce_previous_mode"], "Disabled")
        self.assertEqual(self._read_mode(path), "Disabled")

    def test_noop_when_not_on_spruce(self) -> None:
        path = self._settings("Disabled")
        with patch.object(spruce_conf, "running_on_spruce", return_value=False):
            with patch.object(config, "SPRUCE_CONFIG_JSON", path):
                result = spruce_conf.patch_spruce_mode({})
        self.assertFalse(result["exists"])
        self.assertEqual(self._read_mode(path), "Disabled")

    def test_missing_or_corrupt_settings_are_tolerated(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            with patch.object(spruce_conf, "running_on_spruce", return_value=True):
                missing = Path(temp_dir) / "absent.json"
                with patch.object(config, "SPRUCE_CONFIG_JSON", missing):
                    self.assertFalse(spruce_conf.patch_spruce_mode({})["exists"])
                corrupt = Path(temp_dir) / "corrupt.json"
                corrupt.write_text("{nope", encoding="utf-8")
                with patch.object(config, "SPRUCE_CONFIG_JSON", corrupt):
                    self.assertFalse(spruce_conf.patch_spruce_mode({})["exists"])


class SprucePortTests(unittest.TestCase):
    def test_default_port_avoids_the_sftpgo_port_on_spruce(self) -> None:
        with patch.object(config, "running_on_spruce", return_value=True):
            self.assertEqual(config.default_proxy_port(), config.SPRUCE_DEFAULT_PROXY_PORT)
            self.assertNotEqual(config.default_proxy_port(), 8080)
            self.assertEqual(config.proxy_port({}), config.SPRUCE_DEFAULT_PROXY_PORT)

    def test_default_port_unchanged_elsewhere(self) -> None:
        with patch.object(config, "running_on_spruce", return_value=False):
            self.assertEqual(config.proxy_port({}), 8080)

    def test_explicit_port_still_wins_on_spruce(self) -> None:
        with patch.object(config, "running_on_spruce", return_value=True):
            self.assertEqual(config.proxy_port({"proxy_port": 9000}), 9000)

    def test_port_availability_probe_detects_a_live_listener(self) -> None:
        listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.addCleanup(listener.close)
        listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        listener.bind(("127.0.0.1", 0))
        listener.listen(1)
        taken_port = listener.getsockname()[1]

        self.assertFalse(service.port_is_available({"proxy_port": taken_port}))

    def test_port_availability_probe_accepts_a_free_port(self) -> None:
        probe = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        probe.bind(("127.0.0.1", 0))
        free_port = probe.getsockname()[1]
        probe.close()

        self.assertTrue(service.port_is_available({"proxy_port": free_port}))

    def test_service_status_reports_the_resolved_port(self) -> None:
        written: dict = {}
        with patch.object(service, "save_pid", lambda *_a: None):
            with patch.object(service, "save_service_status", written.update):
                with patch.object(config, "running_on_spruce", return_value=True):
                    service.save_running_service_state(123, {})

        self.assertEqual(written["proxyPort"], config.SPRUCE_DEFAULT_PROXY_PORT)
        self.assertEqual(written["proxyHost"], "127.0.0.1")

    def test_start_reports_a_taken_port_instead_of_dying_silently(self) -> None:
        listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.addCleanup(listener.close)
        listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        listener.bind(("127.0.0.1", 0))
        listener.listen(1)
        taken_port = listener.getsockname()[1]

        with patch.object(service, "discover_service_pids", return_value=[]):
            with self.assertRaises(RuntimeError) as caught:
                service.start_service_process({"proxy_port": taken_port})

        self.assertIn(str(taken_port), str(caught.exception))
        self.assertIn("already in use", str(caught.exception))


# Verbatim copy of spruce 4.3.4's /mnt/SDCARD/.tmp_update/updater, pulled off a real
# Miyoo Mini. The hook is inserted into this exact file at boot, so the tests run against
# the real thing rather than a hand-written approximation.
SPRUCE_UPDATER = """#!/bin/sh

INFO=$(cat /proc/cpuinfo 2> /dev/null)
case $INFO in
    *"sun8i"*) export PLATFORM="A30" ;;
    *"TG5040"*)	export PLATFORM="SmartPro" ;;
    *"TG3040"*)	export PLATFORM="Brick"	;;
    *"TG4040"*)	export PLATFORM="BrickPro"	;;
    *"TG5050"*)	export PLATFORM="SmartProS"	;;
    *"0xd05"*) export PLATFORM="Flip" ;;
    *"0xd04"*) export PLATFORM="Pixel2" ;;
    *) 
        if [ -e /usr/magicx ]; then
            export PLATFORM="Zero28"
        else
            export PLATFORM="MiyooMini" 
        fi
        ;;
esac


if [ "$PLATFORM" = "MiyooMini" ]; then
    /mnt/SDCARD/spruce/scripts/platform/miyoo_mini_startup.sh
else
    cd /mnt/SDCARD/spruce/scripts
    ./runtime.sh
fi
"""


class SpruceBootHookTests(unittest.TestCase):
    def _updater(self):
        temp_dir = tempfile.TemporaryDirectory()
        self.addCleanup(temp_dir.cleanup)
        path = Path(temp_dir.name) / "updater"
        path.write_text(SPRUCE_UPDATER, encoding="utf-8")
        return path

    def test_hook_is_inserted_above_the_dispatch_not_appended(self) -> None:
        path = self._updater()
        platform.install_spruce_boot_hook(path)
        content = path.read_text(encoding="utf-8")

        hook_at = content.index(platform.AUTOSTART_SENTINEL_START)
        # Appending would put it after a dispatch that never returns, so it would never
        # run. Anchored on the shebang rather than any device-specific line.
        self.assertTrue(content.startswith("#!"))
        self.assertLess(hook_at, content.index("./runtime.sh"))
        self.assertIn(str(platform.SPRUCE_AUTOSTART_LAUNCHER), content)
        self.assertIn("&", content[hook_at : content.index("./runtime.sh")])

    def test_original_boot_logic_is_preserved(self) -> None:
        path = self._updater()
        platform.install_spruce_boot_hook(path)
        content = path.read_text(encoding="utf-8")

        for line in SPRUCE_UPDATER.splitlines():
            if line.strip():
                self.assertIn(line, content)

    def test_install_is_idempotent(self) -> None:
        path = self._updater()
        platform.install_spruce_boot_hook(path)
        once = path.read_text(encoding="utf-8")
        platform.install_spruce_boot_hook(path)
        platform.install_spruce_boot_hook(path)
        self.assertEqual(path.read_text(encoding="utf-8"), once)
        self.assertEqual(once.count(platform.AUTOSTART_SENTINEL_START), 1)

    def test_remove_strips_the_block_without_deleting_spruce_boot_file(self) -> None:
        path = self._updater()
        platform.install_spruce_boot_hook(path)
        with patch.object(platform, "running_on_spruce", return_value=True):
            with patch.object(
                platform, "DEFAULT_SPRUCE_STARTUP_SCRIPT", path
            ):
                platform.remove_boot_hook({"startup_script": str(path)})

        content = path.read_text(encoding="utf-8")
        self.assertTrue(path.exists())
        self.assertNotIn(platform.AUTOSTART_SENTINEL_START, content)
        self.assertIn("./runtime.sh", content)

    def test_unrecognised_boot_file_is_refused(self) -> None:
        temp_dir = tempfile.TemporaryDirectory()
        self.addCleanup(temp_dir.cleanup)
        path = Path(temp_dir.name) / "updater"
        path.write_text("not a shell script at all\n", encoding="utf-8")

        with self.assertRaises(ValueError):
            platform.install_spruce_boot_hook(path)
        # Left untouched rather than guessed at.
        self.assertNotIn(platform.AUTOSTART_SENTINEL_START, path.read_text(encoding="utf-8"))

    def test_hook_does_not_depend_on_the_device_name(self) -> None:
        # The insertion point must not be anchored on a per-device line such as spruce's
        # "$PLATFORM" = "MiyooMini" dispatch; the hook is device-independent.
        path = self._updater()
        platform.install_spruce_boot_hook(path)
        block_start = path.read_text(encoding="utf-8").index(platform.AUTOSTART_SENTINEL_START)
        block_end = path.read_text(encoding="utf-8").index(platform.AUTOSTART_SENTINEL_END)
        block = path.read_text(encoding="utf-8")[block_start:block_end]
        for device in ("MiyooMini", "A30", "Brick", "Flip", "PLATFORM"):
            self.assertNotIn(device, block)

    def test_autostart_is_now_supported_on_spruce(self) -> None:
        with patch.object(platform, "running_on_spruce", return_value=True):
            self.assertEqual(
                platform.resolve_startup_script_path({}),
                platform.DEFAULT_SPRUCE_STARTUP_SCRIPT,
            )
            self.assertTrue(platform.autostart_supported({}))
            self.assertEqual(
                platform.autostart_command({}),
                (str(platform.SPRUCE_AUTOSTART_LAUNCHER),),
            )


class SpruceUpdateTests(unittest.TestCase):
    ASSETS = [
        {
            "name": "RAOfflineProxy-Onion-v1.11.1-alpha1.zip",
            "browser_download_url": "https://example.test/onion.zip",
        },
        {
            "name": "RAOfflineProxy-Spruce-v1.11.1-alpha1.zip",
            "browser_download_url": "https://example.test/spruce.zip",
        },
    ]

    def test_validate_platform_accepts_spruce(self) -> None:
        self.assertEqual(update.validate_platform("Spruce"), update.PLATFORM_SPRUCE)

    def test_asset_lookup_does_not_cross_match_onion_and_spruce(self) -> None:
        self.assertEqual(
            update.find_platform_asset_url(update.PLATFORM_SPRUCE, self.ASSETS),
            "https://example.test/spruce.zip",
        )
        self.assertEqual(
            update.find_platform_asset_url(update.PLATFORM_ONION, self.ASSETS),
            "https://example.test/onion.zip",
        )


class SpruceLogMetadataTests(unittest.TestCase):
    def test_platform_label_and_version_use_spruce_file(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            version_file = Path(temp_dir) / "spruce"
            version_file.write_text("4.3.3\n", encoding="utf-8")
            with patch.object(config, "SPRUCE_VERSION_FILE", version_file):
                with patch.object(config, "running_on_spruce", return_value=True):
                    with patch.object(config, "spruce_platform", return_value="MiyooMini"):
                        self.assertEqual(log_uploader._platform_label(), "spruce")
                        self.assertEqual(log_uploader._os_version_value("spruce"), "4.3.3")
                        self.assertEqual(log_uploader._device_label("spruce"), "MiyooMini")


if __name__ == "__main__":
    unittest.main()
