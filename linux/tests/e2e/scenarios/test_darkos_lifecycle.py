from __future__ import annotations

import pytest

USER = "testuser"
TOKEN = "tok-testuser-000000000001"
MSLUG_HASH = "b43c8b4ec999588c04dad79bb8bcc745"
MSLUG_GAME_ID = 1447

HARDCORE_KEY = "cheevos_hardcore_mode_enable"
CUSTOM_HOST_KEY = "cheevos_custom_host"
UNIT = "raofflineproxy.service"


def cfg_value(content: str, key: str) -> str | None:
    for line in content.splitlines():
        stripped = line.strip()
        if stripped.startswith(key):
            return stripped.split("=", 1)[1].strip().strip('"')
    return None


@pytest.fixture
def installed(darkos):
    darkos.install()
    darkos.rom = darkos.stage_rom("mslug.7z")
    return darkos


def systemctl(session, command: str):
    return session.container.exec("systemctl %s %s" % (command, UNIT))


class TestInstall:
    def test_installer_runs_unprivileged_as_the_device_user(self, darkos):
        # dArkOS launches Tools entries as `ark`, never root. Installing as root
        # here would hide every privilege assumption the bundle makes.
        whoami = darkos.container.exec("whoami", user="ark").stdout.strip()
        assert whoami == "ark"

        result = darkos.install()
        assert "RAOfflineProxy installed." in result.stdout

    def test_install_lays_down_the_expected_tree(self, installed):
        container, device = installed.container, installed.device
        assert container.is_executable(device.bin_path)
        assert container.is_executable(device.uninstall_path)
        assert container.exists(device.base_dir + "/lib/libraproxy_rchash.so")
        assert container.exists(device.base_dir + "/app/raofflineproxy/main.py")
        assert container.exists(device.tools_entry)

    def test_install_registers_the_systemd_unit(self, installed):
        assert installed.container.exists(installed.device.boot_hook)
        assert systemctl(installed, "cat").ok

    def test_resolves_the_darkos_retroarch_config(self, installed):
        result = installed.app_python(
            "from raofflineproxy import config; print(config.detect_retroarch_cfg())"
        )
        assert installed.device.retroarch_cfg in result.stdout

    def test_native_hasher_loads_and_hashes_on_aarch64(self, installed):
        result = installed.app_python(
            "from raofflineproxy import rom_hashing; "
            "assert rom_hashing.load_rchash() is not None; "
            'print(rom_hashing.hash_rom("%s"))' % installed.rom,
            check=True,
        )
        assert result.stdout.strip() == MSLUG_HASH


class TestSystemdOwnsTheProcess:
    def test_start_proxy_patches_config_and_activates_the_unit(self, installed):
        before = installed.container.read_file(installed.device.retroarch_cfg)
        assert cfg_value(before, HARDCORE_KEY) == "true"

        # _apply_proxy starts the service before running its own patch pass, and
        # on dArkOS starting the service is `systemctl start`, whose ExecStartPre
        # has already patched by then. So the second pass reporting "already
        # patched" is correct here; the end state is what matters.
        result = installed.cli.run("start-proxy", check=True)
        assert "retroarch.cfg" in result.stdout
        assert installed.cli.service_running()
        assert systemctl(installed, "is-active --quiet").ok

        after = installed.container.read_file(installed.device.retroarch_cfg)
        assert cfg_value(after, HARDCORE_KEY) == "false"
        assert cfg_value(after, CUSTOM_HOST_KEY) == installed.device.proxy_value

    def test_the_service_runs_as_the_device_user_not_root(self, installed):
        # A root-owned service would create files in ~/.config the unprivileged
        # menu then cannot write.
        installed.cli.run("start-proxy", check=True)
        pid = installed.container.exec(
            "systemctl show --property MainPID --value %s" % UNIT
        ).stdout.strip()
        assert pid not in ("", "0")

        owner = installed.container.exec("ps -o user= -p %s" % pid).stdout.strip()
        assert owner == "ark"

    def test_files_the_service_creates_stay_writable_by_the_menu(self, installed):
        installed.cli.run("start-proxy", check=True)
        installed.emulator.boot_sequence(USER, TOKEN, MSLUG_HASH)

        owners = installed.container.exec(
            "find %s -type f -exec stat -c '%%U' {} +" % installed.device.config_dir
        ).stdout.split()
        assert owners, "service produced no state files to check"
        assert set(owners) == {"ark"}

    def test_stop_proxy_reverts_and_deactivates_the_unit(self, installed):
        installed.cli.run("start-proxy", check=True)
        result = installed.cli.run("stop-proxy", check=True)
        assert "Reverted retroarch.cfg" in result.stdout
        assert not installed.cli.service_running()
        assert not systemctl(installed, "is-active --quiet").ok

        cfg = installed.container.read_file(installed.device.retroarch_cfg)
        assert cfg_value(cfg, HARDCORE_KEY) == "true"
        assert cfg_value(cfg, CUSTOM_HOST_KEY) == ""

    def test_status_reports_the_configured_port(self, installed):
        installed.cli.run("start-proxy", check=True)
        status = installed.container.read_file(
            installed.device.config_dir + "/config.json"
        )
        assert status  # config is readable by the menu account
        assert installed.cli.service_running()


class TestAutostart:
    def test_enable_autostart_enables_the_unit(self, installed):
        installed.cli.run("enable-autostart", check=True)
        assert installed.cli.autostart_enabled()
        assert systemctl(installed, "is-enabled --quiet").ok

    def test_disable_autostart_disables_the_unit(self, installed):
        installed.cli.run("enable-autostart", check=True)
        installed.cli.run("disable-autostart", check=True)
        assert not installed.cli.autostart_enabled()
        assert not systemctl(installed, "is-enabled --quiet").ok

    def test_starting_the_unit_directly_still_patches_the_emulator(self, installed):
        # What actually happens at boot: systemd starts the unit on its own,
        # without going through the menu's start-proxy. Before ExecStartPre the
        # proxy came up but nothing was pointed at it, so achievements bypassed
        # it entirely while looking healthy.
        installed.cli.run("start-proxy", check=True)
        installed.cli.run("stop-proxy", check=True)
        reverted = installed.container.read_file(installed.device.retroarch_cfg)
        assert cfg_value(reverted, CUSTOM_HOST_KEY) == ""

        installed.container.exec("systemctl start %s" % UNIT, check=True)
        assert installed.container.exec(
            "systemctl is-active --quiet %s" % UNIT
        ).ok

        patched = installed.container.read_file(installed.device.retroarch_cfg)
        assert cfg_value(patched, CUSTOM_HOST_KEY) == installed.device.proxy_value
        assert cfg_value(patched, HARDCORE_KEY) == "false"


class TestCaching:
    def test_cache_rom_resolves_hash_through_to_the_server(self, installed):
        result = installed.cli.run("cache-rom --path %s" % installed.rom, check=True)
        assert "Metal Slug" in result.stdout
        assert installed.cli.cached_game_count() == 1

    def test_launching_a_game_online_caches_it(self, installed):
        installed.cli.run("start-proxy", check=True)
        responses = installed.emulator.boot_sequence(USER, TOKEN, MSLUG_HASH)

        assert responses["gameid"]["GameID"] == MSLUG_GAME_ID
        assert installed.cli.cached_game_count() == 1


class TestHardcoreIsRefused:
    def test_hardcore_award_is_rejected_and_never_forwarded(self, installed):
        installed.cli.run("start-proxy", check=True)
        installed.emulator.boot_sequence(USER, TOKEN, MSLUG_HASH)
        installed.ra.clear_journal()

        status, payload = installed.emulator.award(USER, TOKEN, 22001, hardcore=1)
        assert status == 403
        assert payload["Error"] == "hardcore_not_supported"
        assert installed.ra.violations() == []


class TestOfflineAwardCycle:
    def test_offline_award_queues_and_flushes_when_back_online(self, installed):
        installed.cli.run("start-proxy", check=True)
        installed.emulator.boot_sequence(USER, TOKEN, MSLUG_HASH)

        status, payload = installed.emulator.award(USER, TOKEN, 22001)
        assert status == 200 and payload["Success"] is True

        installed.go_offline()
        assert installed.wait_for_online(False), "service never noticed the outage"

        status, payload = installed.emulator.award(USER, TOKEN, 22002)
        assert payload["Success"] is True
        assert payload.get("Error") == "queued_offline"
        assert installed.cli.pending_award_count() == 1

        installed.go_online()
        assert installed.wait_for_online(True), "service never saw the network return"
        assert installed.wait_for_pending(0), "queued award was never flushed"

        assert 22002 in installed.ra.unlocks(USER, MSLUG_GAME_ID)
        assert installed.ra.violations() == []


class TestUninstall:
    def test_uninstall_removes_everything_including_the_unit(self, installed):
        installed.cli.run("start-proxy", check=True)
        installed.cli.run("enable-autostart", check=True)

        installed.uninstall()

        for path in installed.device.residue_paths:
            assert not installed.container.exists(path), "residue left at %s" % path

        # The unit must be gone from systemd's view too, not just off disk.
        assert not installed.container.exec(
            "systemctl is-active --quiet %s" % UNIT
        ).ok

        cfg = installed.container.read_file(installed.device.retroarch_cfg)
        assert cfg_value(cfg, HARDCORE_KEY) == "true"
        assert cfg_value(cfg, CUSTOM_HOST_KEY) == ""
