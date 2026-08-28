from __future__ import annotations

import pytest

USER = "testuser"
TOKEN = "tok-testuser-000000000001"
MSLUG_HASH = "b43c8b4ec999588c04dad79bb8bcc745"
MSLUG_GAME_ID = 1447

RA_HARDCORE_KEY = "cheevos_hardcore_mode_enable"
RA_HOST_KEY = "cheevos_custom_host"
SYS_HARDCORE_KEY = "global.retroachievements.hardcore"
SYS_RA_HARDCORE_KEY = "global.retroarch.cheevos_hardcore_mode_enable"
PPSSPP_HARDCORE_KEY = "AchievementsChallengeMode"
PPSSPP_HOST_KEY = "AchievementsHost"
DOLPHIN_HARDCORE_KEY = "HardcoreEnabled"
DOLPHIN_HOST_KEY = "HostUrl"


def cfg_value(content: str, key: str) -> str | None:
    for line in content.splitlines():
        stripped = line.strip()
        if stripped.startswith(key):
            return stripped.split("=", 1)[1].strip().strip('"')
    return None


def ini_value(content: str, key: str) -> str | None:
    for line in content.splitlines():
        stripped = line.strip()
        if stripped.split("=")[0].strip() == key:
            return stripped.split("=", 1)[1].strip()
    return None


@pytest.fixture
def installed(rocknix):
    rocknix.install()
    rocknix.rom = rocknix.stage_rom("mslug.7z")
    return rocknix


class TestInstall:
    def test_installer_runs_under_busybox(self, rocknix):
        applets = rocknix.container.exec("which base64 tar awk").stdout
        assert "/opt/busybox-bin/base64" in applets

        result = rocknix.install()
        assert "RAOfflineProxy installed." in result.stdout

    def test_install_lays_down_the_expected_tree(self, installed):
        container, device = installed.container, installed.device
        assert container.is_executable(device.bin_path)
        assert container.is_executable(device.uninstall_path)
        assert container.exists(device.base_dir + "/lib/libraproxy_rchash.so")
        assert container.exists(device.base_dir + "/app/raofflineproxy/main.py")
        assert container.is_executable(device.tools_entry)

    def test_detects_rocknix_and_its_config_paths(self, installed):
        result = installed.app_python(
            "from raofflineproxy import config;"
            "print(config.running_on_rocknix());"
            "print(config.detect_retroarch_cfg());"
            "print(config.detect_batocera_conf({}));"
            "print(config.detect_ppsspp_ini({}));"
            "print(config.detect_dolphin_ini({}));"
            "print(config.resolve_config_dir())",
            check=True,
        )
        lines = result.stdout.strip().splitlines()
        device = installed.device
        assert lines[0] == "True"
        assert lines[1] == device.retroarch_cfg
        assert lines[2] == device.batocera_conf
        assert lines[3] == device.ppsspp_ini
        assert lines[4] == device.dolphin_ini
        assert lines[5] == device.config_dir

    def test_system_cfg_stands_in_for_batocera_conf(self, installed):
        """ROCKNIX has no batocera.conf; system.cfg carries the same keys."""
        result = installed.app_python(
            "from raofflineproxy import config;"
            "print(config.detect_batocera_conf({}))",
            check=True,
        )
        assert result.stdout.strip().endswith("/system/configs/system.cfg")

    def test_credentials_are_read_from_system_cfg(self, installed):
        result = installed.app_python(
            "from raofflineproxy import retroarch_cfg as rc;"
            "print(rc.load_rocknix_system_token_credentials('%s'))"
            % installed.device.batocera_conf,
            check=True,
        )
        assert USER in result.stdout
        assert TOKEN in result.stdout

    def test_native_hasher_loads_and_hashes_on_aarch64(self, installed):
        result = installed.app_python(
            "from raofflineproxy import rom_hashing;"
            "assert rom_hashing.load_rchash() is not None;"
            "print(rom_hashing.hash_rom('%s'))" % installed.rom,
            check=True,
        )
        assert result.stdout.strip() == MSLUG_HASH

    def test_vendored_pygame_matches_the_interpreter_abi(self, installed):
        """The bundle vendors cp313/cp314 extension modules; a mismatch is silent
        until the menu is launched on device."""
        version = installed.container.exec(
            "python3 -c 'import sys; print(\"%d%d\" % sys.version_info[:2])'",
            check=True,
        ).stdout.strip()
        assert installed.container.exists(
            "%s/app/pygame/base.cpython-%s-aarch64-linux-gnu.so"
            % (installed.device.base_dir, version)
        )

        result = installed.container.exec(
            "cd %s/app && python3 -c 'import pygame; print(pygame.version.ver)'"
            % installed.device.base_dir,
            check=True,
        )
        assert result.stdout.strip()


class TestMultiEmulatorPatching:
    def test_start_proxy_patches_every_supported_emulator(self, installed):
        installed.cli.run("start-proxy", check=True)
        assert installed.cli.service_running()
        device, container = installed.device, installed.container

        retroarch = container.read_file(device.retroarch_cfg)
        assert cfg_value(retroarch, RA_HARDCORE_KEY) == "false"
        assert cfg_value(retroarch, RA_HOST_KEY) == installed.device.proxy_value

        system = container.read_file(device.batocera_conf)
        assert cfg_value(system, SYS_HARDCORE_KEY) == "0"
        assert cfg_value(system, SYS_RA_HARDCORE_KEY) == "false"

        ppsspp = container.read_file(device.ppsspp_ini)
        assert ini_value(ppsspp, PPSSPP_HARDCORE_KEY) == "False"
        assert ini_value(ppsspp, PPSSPP_HOST_KEY) == installed.device.proxy_value

        dolphin = container.read_file(device.dolphin_ini)
        assert ini_value(dolphin, DOLPHIN_HARDCORE_KEY) == "False"
        assert ini_value(dolphin, DOLPHIN_HOST_KEY) == installed.device.proxy_value

    def test_stop_proxy_reverts_every_emulator_and_restores_hardcore(self, installed):
        installed.cli.run("start-proxy", check=True)
        installed.cli.run("stop-proxy", check=True)
        assert not installed.cli.service_running()
        device, container = installed.device, installed.container

        retroarch = container.read_file(device.retroarch_cfg)
        assert cfg_value(retroarch, RA_HARDCORE_KEY) == "true"
        assert cfg_value(retroarch, RA_HOST_KEY) == ""

        system = container.read_file(device.batocera_conf)
        assert cfg_value(system, SYS_HARDCORE_KEY) == "1"
        assert cfg_value(system, SYS_RA_HARDCORE_KEY) == "true"

        ppsspp = container.read_file(device.ppsspp_ini)
        assert ini_value(ppsspp, PPSSPP_HARDCORE_KEY) == "True"
        assert ini_value(ppsspp, PPSSPP_HOST_KEY) == ""

        dolphin = container.read_file(device.dolphin_ini)
        assert ini_value(dolphin, DOLPHIN_HARDCORE_KEY) == "True"
        assert ini_value(dolphin, DOLPHIN_HOST_KEY) is None

    def test_hardcore_off_beforehand_stays_off_after_stop(self, installed):
        installed.container.exec(
            "sed -i 's/%s = \"true\"/%s = \"false\"/' %s"
            % (RA_HARDCORE_KEY, RA_HARDCORE_KEY, installed.device.retroarch_cfg),
            check=True,
        )
        installed.container.exec(
            "sed -i 's/^%s=1/%s=0/' %s"
            % (SYS_HARDCORE_KEY, SYS_HARDCORE_KEY, installed.device.batocera_conf),
            check=True,
        )
        installed.cli.run("start-proxy", check=True)
        installed.cli.run("stop-proxy", check=True)

        retroarch = installed.container.read_file(installed.device.retroarch_cfg)
        assert cfg_value(retroarch, RA_HARDCORE_KEY) == "false"
        system = installed.container.read_file(installed.device.batocera_conf)
        assert cfg_value(system, SYS_HARDCORE_KEY) == "0"


class TestAutostart:
    def test_enable_autostart_writes_the_rocknix_boot_hook(self, installed):
        installed.cli.run("enable-autostart", check=True)
        assert installed.cli.autostart_enabled()
        assert installed.container.exists(installed.device.boot_hook)
        hook = installed.container.read_file(installed.device.boot_hook)
        assert "raofflineproxy" in hook

    def test_disable_autostart_removes_the_hook(self, installed):
        installed.cli.run("enable-autostart", check=True)
        installed.cli.run("disable-autostart", check=True)
        assert not installed.cli.autostart_enabled()

    def test_boot_reconcile_applies_only_when_enabled(self, installed):
        installed.cli.run("disable-autostart", check=True)
        installed.cli.run("boot-reconcile", check=True)
        assert not installed.cli.service_running()

        installed.cli.run("enable-autostart", check=True)
        installed.cli.run("boot-reconcile", check=True)
        assert installed.cli.service_running()
        installed.cli.run("stop-proxy")


class TestCaching:
    def test_cache_rom_resolves_hash_through_to_the_server(self, installed):
        result = installed.cli.run("cache-rom --path %s" % installed.rom, check=True)
        assert "Metal Slug" in result.stdout
        assert installed.cli.cached_game_count() == 1
        assert installed.ra.actions() == ["gameid", "patch", "unlocks", "achievementsets"]

    def test_clear_cache_empties_the_store(self, installed):
        installed.cli.run("cache-rom --path %s" % installed.rom, check=True)
        installed.cli.run("clear-cached-games", check=True)
        assert installed.cli.cached_game_count() == 0

    def test_launching_a_game_online_caches_it(self, installed):
        installed.cli.run("start-proxy", check=True)
        responses = installed.emulator.boot_sequence(USER, TOKEN, MSLUG_HASH)
        assert responses["gameid"]["GameID"] == MSLUG_GAME_ID
        assert len(responses["patch"]["PatchData"]["Achievements"]) == 3
        assert installed.cli.cached_game_count() == 1


class TestHardcoreIsRefused:
    def test_hardcore_award_is_rejected_and_never_forwarded(self, installed):
        installed.cli.run("start-proxy", check=True)
        installed.emulator.boot_sequence(USER, TOKEN, MSLUG_HASH)
        installed.ra.clear_journal()

        status, payload = installed.emulator.award(USER, TOKEN, 22001, hardcore=1)
        assert status == 403
        assert payload["Error"] == "hardcore_not_supported"
        assert all(
            entry.get("params", {}).get("h") != "1" for entry in installed.ra.journal()
        )
        assert installed.ra.violations() == []


class TestOfflineAwardCycle:
    def test_offline_award_queues_and_flushes_when_back_online(self, installed):
        installed.cli.run("start-proxy", check=True)
        installed.emulator.boot_sequence(USER, TOKEN, MSLUG_HASH)

        status, payload = installed.emulator.award(USER, TOKEN, 22001)
        assert status == 200 and payload["Success"] is True
        assert installed.ra.unlocks(USER, MSLUG_GAME_ID) == [22001]

        installed.go_offline()
        assert installed.wait_for_online(False), "service never noticed the outage"

        status, payload = installed.emulator.award(USER, TOKEN, 22002)
        assert payload["Success"] is True
        assert payload.get("Error") == "queued_offline"
        assert installed.cli.pending_award_count() == 1
        assert 22002 not in installed.ra.unlocks(USER, MSLUG_GAME_ID)

        _status, unlocks = installed.emulator.unlocks(USER, TOKEN, MSLUG_GAME_ID)
        assert sorted(unlocks["UserUnlocks"]) == [22001, 22002]

        installed.go_online()
        assert installed.wait_for_online(True), "service never saw the network return"
        assert installed.wait_for_pending(0), "queued award was never flushed"

        assert 22002 in installed.ra.unlocks(USER, MSLUG_GAME_ID)
        assert installed.ra.violations() == [], "proxy sent something RA would reject"

    def test_offline_reads_are_served_from_cache(self, installed):
        installed.cli.run("start-proxy", check=True)
        installed.emulator.boot_sequence(USER, TOKEN, MSLUG_HASH)

        installed.go_offline()
        assert installed.wait_for_online(False)

        _status, payload = installed.emulator.game_id(MSLUG_HASH)
        assert payload["GameID"] == MSLUG_GAME_ID


class TestUninstall:
    def test_uninstall_removes_everything_and_leaves_configs_reverted(self, installed):
        installed.cli.run("start-proxy", check=True)
        installed.cli.run("enable-autostart", check=True)

        installed.uninstall()

        for path in installed.device.residue_paths:
            assert not installed.container.exists(path), "residue left at %s" % path

        device, container = installed.device, installed.container
        assert cfg_value(container.read_file(device.retroarch_cfg), RA_HARDCORE_KEY) == "true"
        assert cfg_value(container.read_file(device.batocera_conf), SYS_HARDCORE_KEY) == "1"
        assert ini_value(container.read_file(device.ppsspp_ini), PPSSPP_HARDCORE_KEY) == "True"
        assert ini_value(container.read_file(device.dolphin_ini), DOLPHIN_HARDCORE_KEY) == "True"
