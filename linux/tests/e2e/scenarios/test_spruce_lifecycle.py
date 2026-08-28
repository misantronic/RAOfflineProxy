from __future__ import annotations

import pytest

from linux.tests.e2e.scenarios._miyoo_common import MiyooLifecycle

SPRUCE_VERSION_FILE = "/mnt/SDCARD/spruce/spruce"
PLATFORM_CFG_DIR = "/mnt/SDCARD/RetroArch/platform"
LEGACY_CFG = "/mnt/SDCARD/RetroArch/.retroarch/retroarch.cfg"


@pytest.fixture
def installed(spruce):
    spruce.install()
    spruce.rom = spruce.stage_rom("mslug.7z")
    return spruce


class TestSpruceLifecycle(MiyooLifecycle):
    pass


class TestSpruceSpecific:
    def test_detects_spruce_and_not_onion(self, installed):
        """spruce reuses Onion's App/ layout, so the version files are the only
        thing separating them."""
        probe = installed.container.exec(
            "cd %s && . ./common.sh && prepare_env >/dev/null 2>&1 && "
            'resolve_python_bin && "$RESOLVED_PYTHON_BIN" -c '
            "'from raofflineproxy import config; "
            "print(config.running_on_spruce(), config.running_on_onion(), "
            "config.running_on_allium())'" % installed.device.base_dir,
            check=True,
        )
        assert probe.stdout.strip().splitlines()[-1] == "True False False"

    def test_onion_version_file_wins_when_both_markers_exist(self, installed):
        """Reinstalling Onion over a card that once ran spruce leaves
        /mnt/SDCARD/spruce behind; onionVersion settles the tie."""
        installed.container.exec(
            "mkdir -p /mnt/SDCARD/.tmp_update/onionVersion && "
            "echo 'v4.4.0-beta-20260120' > "
            "/mnt/SDCARD/.tmp_update/onionVersion/version.txt",
            check=True,
        )
        probe = installed.container.exec(
            "cd %s && . ./common.sh && prepare_env >/dev/null 2>&1 && "
            'resolve_python_bin && "$RESOLVED_PYTHON_BIN" -c '
            "'from raofflineproxy import config; "
            "print(config.running_on_spruce(), config.running_on_onion())'"
            % installed.device.base_dir,
            check=True,
        )
        assert probe.stdout.strip().splitlines()[-1] == "False True"

    def test_proxy_moves_off_8080_because_sftpgo_owns_it(self, installed):
        """spruce ships SFTPGo bound to 0.0.0.0:8080, so the usual default can
        never bind and default_proxy_port() returns 8099 instead."""
        installed.cli.run("start-proxy", check=True)
        cfg = installed.container.read_file(installed.device.retroarch_cfg)
        assert 'cheevos_custom_host = "127.0.0.1:8099"' in cfg

    def test_patches_the_per_platform_config_not_the_legacy_one(self, installed):
        """spruce launches RetroArch with --config, so .retroarch/retroarch.cfg
        is never read and patching it would be a no-op on device."""
        installed.cli.run("start-proxy", check=True)

        patched = installed.container.read_file(installed.device.retroarch_cfg)
        assert 'cheevos_custom_host = "%s"' % installed.device.proxy_value in patched

        legacy = installed.container.read_file(LEGACY_CFG)
        assert "cheevos_custom_host" not in legacy

    def test_platform_resolves_to_miyoomini_by_default(self, installed):
        probe = installed.container.exec(
            "cd %s && . ./common.sh && prepare_env >/dev/null 2>&1 && "
            'resolve_python_bin && "$RESOLVED_PYTHON_BIN" -c '
            "'from raofflineproxy import config; "
            "print(config.spruce_platform()); print(config.spruce_retroarch_cfg())'"
            % installed.device.base_dir,
            check=True,
        )
        lines = probe.stdout.strip().splitlines()
        assert lines[-2] == "MiyooMini"
        assert lines[-1] == PLATFORM_CFG_DIR + "/retroarch-MiyooMini.cfg"

    def test_boot_hook_is_inserted_into_the_updater(self, installed):
        """spruce has no drop-in boot directory: .tmp_update/updater is the whole
        entry point and it dispatches without returning, so the hook goes in
        above the dispatch."""
        original = installed.container.read_file(installed.device.boot_hook)
        installed.cli.run("enable-autostart", check=True)
        patched = installed.container.read_file(installed.device.boot_hook)

        assert patched != original
        assert "autostart-launch.sh" in patched
        assert patched.startswith("#!")
