from __future__ import annotations

import pytest

from linux.tests.e2e.scenarios._miyoo_common import MiyooLifecycle

OTA_ARCHIVE = "/mnt/SDCARD/allium-ota.zip"


@pytest.fixture
def installed(allium):
    allium.install()
    allium.rom = allium.stage_rom("mslug.7z")
    return allium


class TestAlliumLifecycle(MiyooLifecycle):
    pass


class TestAlliumSpecific:
    def test_installs_as_a_pak_not_into_the_shared_app_tree(self, installed):
        assert installed.device.base_dir.endswith("RAOfflineProxy.pak")
        assert installed.container.exists(installed.device.base_dir)
        assert not installed.container.exists("/mnt/SDCARD/App/RAOfflineProxy")

    def test_detects_allium_from_its_marker_directory(self, installed):
        probe = installed.container.exec(
            "cd %s && . ./common.sh && prepare_env >/dev/null 2>&1 && "
            'resolve_python_bin && "$RESOLVED_PYTHON_BIN" -c '
            "'from raofflineproxy import config; "
            "print(config.running_on_allium(), config.running_on_spruce(), "
            "config.running_on_shared_miyoo_stack())'" % installed.device.base_dir,
            check=True,
        )
        assert probe.stdout.strip().splitlines()[-1] == "True False True"

    def test_boot_hook_shares_the_updater_path_with_spruce(self, installed):
        """Same file spruce uses; platform.py dispatches on running_on_allium()
        rather than path equality."""
        assert installed.device.boot_hook == "/mnt/SDCARD/.tmp_update/updater"
        installed.cli.run("enable-autostart", check=True)
        hook = installed.container.read_file(installed.device.boot_hook)
        assert "RAOfflineProxy.pak/autostart-launch.sh" in hook

    def test_boot_hook_skips_autostart_during_an_ota(self, installed):
        """Allium's OTA extracts over the whole card and reboots, so the hook
        must not start anything while the archive is staged."""
        installed.cli.run("enable-autostart", check=True)
        hook = installed.container.read_file(installed.device.boot_hook)
        assert OTA_ARCHIVE in hook
        assert '[ ! -f "%s" ]' % OTA_ARCHIVE in hook
