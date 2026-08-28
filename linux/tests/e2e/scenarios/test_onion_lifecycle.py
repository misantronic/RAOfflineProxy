from __future__ import annotations

import pytest

from linux.tests.e2e.scenarios._miyoo_common import MiyooLifecycle

ONION_VERSION_FILE = "/mnt/SDCARD/.tmp_update/onionVersion/version.txt"
CHECKOFF_SCRIPT = "/mnt/SDCARD/.tmp_update/checkoff/raofflineproxy.sh"


@pytest.fixture
def installed(onion):
    onion.install()
    onion.rom = onion.stage_rom("mslug.7z")
    return onion


class TestOnionLifecycle(MiyooLifecycle):
    pass


class TestOnionSpecific:
    def test_detects_onion_and_not_its_siblings(self, installed):
        result = installed.cli.run(
            "browser-root", check=True
        )
        assert result.ok
        probe = installed.container.exec(
            "cd %s && . ./common.sh && prepare_env >/dev/null 2>&1 && "
            'resolve_python_bin && "$RESOLVED_PYTHON_BIN" -c '
            "'from raofflineproxy import config; "
            "print(config.running_on_onion(), config.running_on_spruce(), "
            "config.running_on_allium(), config.running_on_shared_miyoo_stack())'"
            % installed.device.base_dir,
            check=True,
        )
        assert probe.stdout.strip().splitlines()[-1] == "True False False True"

    def test_boot_hook_lives_in_the_startup_directory(self, installed):
        installed.cli.run("enable-autostart", check=True)
        assert installed.container.exists(installed.device.boot_hook)
        hook = installed.container.read_file(installed.device.boot_hook)
        assert "autostart-launch.sh" in hook

    def test_unsupported_onion_build_refuses_to_start(self, installed):
        """v4.3.1-1 ships a RetroArch whose achievements client mishandles a
        custom host, so the app refuses rather than silently misbehaving."""
        installed.container.exec(
            "echo 'v4.3.1-1' > %s" % ONION_VERSION_FILE, check=True
        )
        result = installed.cli.run("start-proxy")
        assert "Unsupported OnionOS build" in result.stdout + result.stderr
        assert not installed.cli.service_running()

        cfg = installed.container.read_file(installed.device.retroarch_cfg)
        assert 'cheevos_hardcore_mode_enable = "true"' in cfg

    def test_supported_onion_build_starts(self, installed):
        version = installed.container.read_file(ONION_VERSION_FILE).strip()
        assert version.startswith("v4.4.0")
        installed.cli.run("start-proxy", check=True)
        assert installed.cli.service_running()
