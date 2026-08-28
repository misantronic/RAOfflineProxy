from __future__ import annotations

import pytest

USER = "testuser"
TOKEN = "tok-testuser-000000000001"
MSLUG_HASH = "b43c8b4ec999588c04dad79bb8bcc745"
MSLUG_GAME_ID = 1447

HARDCORE_KEY = "cheevos_hardcore_mode_enable"
CUSTOM_HOST_KEY = "cheevos_custom_host"


def cfg_value(content: str, key: str) -> str | None:
    for line in content.splitlines():
        stripped = line.strip()
        if stripped.startswith(key):
            return stripped.split("=", 1)[1].strip().strip('"')
    return None


def conf_value(content: str, key: str) -> str | None:
    for line in content.splitlines():
        if line.strip().startswith(key + "="):
            return line.split("=", 1)[1].strip().strip('"')
    return None


@pytest.fixture
def installed(knulli):
    knulli.install()
    knulli.rom = knulli.stage_rom("mslug.7z")
    return knulli


class TestInstall:
    def test_self_extracting_installer_runs_under_busybox(self, knulli):
        applets = knulli.container.exec("which base64 tar awk").stdout
        assert "/opt/busybox-bin/base64" in applets
        assert "/opt/busybox-bin/tar" in applets

        result = knulli.install()
        assert "RAOfflineProxy installed." in result.stdout

    def test_install_lays_down_the_expected_tree(self, installed):
        container, device = installed.container, installed.device
        assert container.is_executable(device.bin_path)
        assert container.is_executable(device.uninstall_path)
        assert container.exists(device.base_dir + "/lib/libraproxy_rchash.so")
        assert container.exists(device.base_dir + "/app/raofflineproxy/main.py")
        assert container.exists(device.tools_entry)

    def test_detects_knulli_from_os_release(self, installed):
        result = installed.container.exec(
            "cd %s/app && python3 -c "
            "'from raofflineproxy import config; print(config.detect_retroarch_cfg())'"
            % installed.device.base_dir
        )
        assert installed.device.retroarch_cfg in result.stdout

    def test_native_hasher_loads_and_hashes_on_aarch64(self, installed):
        result = installed.container.exec(
            "cd %s/app && python3 -c "
            '\'from raofflineproxy import rom_hashing; '
            'assert rom_hashing.load_rchash() is not None; '
            'print(rom_hashing.hash_rom("%s"))\''
            % (installed.device.base_dir, installed.rom),
            check=True,
        )
        assert result.stdout.strip() == MSLUG_HASH


class TestProxyLifecycle:
    def test_start_proxy_patches_configs_and_runs(self, installed):
        before = installed.container.read_file(installed.device.retroarch_cfg)
        assert cfg_value(before, HARDCORE_KEY) == "true"

        result = installed.cli.run("start-proxy", check=True)
        assert "Patched retroarch.cfg" in result.stdout
        assert installed.cli.service_running()

        after = installed.container.read_file(installed.device.retroarch_cfg)
        assert cfg_value(after, HARDCORE_KEY) == "false"
        assert cfg_value(after, CUSTOM_HOST_KEY) == "127.0.0.1:8080"

    def test_start_proxy_disables_hardcore_in_batocera_conf(self, installed):
        installed.cli.run("start-proxy", check=True)
        conf = installed.container.read_file(installed.device.batocera_conf)
        assert conf_value(conf, "global.retroachievements.hardcore") == "0"
        assert conf_value(conf, "global.retroarch.cheevos_hardcore_mode_enable") == "false"

    def test_stop_proxy_reverts_and_restores_hardcore(self, installed):
        installed.cli.run("start-proxy", check=True)
        result = installed.cli.run("stop-proxy", check=True)
        assert "Reverted retroarch.cfg" in result.stdout
        assert not installed.cli.service_running()

        cfg = installed.container.read_file(installed.device.retroarch_cfg)
        assert cfg_value(cfg, HARDCORE_KEY) == "true"
        assert cfg_value(cfg, CUSTOM_HOST_KEY) == ""

        conf = installed.container.read_file(installed.device.batocera_conf)
        assert conf_value(conf, "global.retroachievements.hardcore") == "1"
        assert conf_value(conf, "global.retroarch.cheevos_hardcore_mode_enable") == "true"

    def test_hardcore_off_beforehand_stays_off_after_stop(self, installed):
        installed.container.exec(
            "sed -i 's/%s = \"true\"/%s = \"false\"/' %s"
            % (HARDCORE_KEY, HARDCORE_KEY, installed.device.retroarch_cfg),
            check=True,
        )
        installed.cli.run("start-proxy", check=True)
        installed.cli.run("stop-proxy", check=True)
        cfg = installed.container.read_file(installed.device.retroarch_cfg)
        assert cfg_value(cfg, HARDCORE_KEY) == "false"


class TestAutostart:
    def test_enable_autostart_writes_boot_hook(self, installed):
        installed.cli.run("enable-autostart", check=True)
        assert installed.cli.autostart_enabled()
        hook = installed.container.read_file(installed.device.boot_hook)
        assert "raofflineproxy" in hook

    def test_disable_autostart_leaves_hook_inert(self, installed):
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

    def test_unknown_hash_reports_no_game(self, installed):
        installed.cli.run("start-proxy", check=True)
        _status, payload = installed.emulator.game_id("d" * 32)
        assert payload["GameID"] == 0


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

        _status, payload = installed.emulator.patch(USER, TOKEN, MSLUG_GAME_ID)
        assert payload["Success"] is True


class TestUninstall:
    def test_uninstall_removes_everything_and_leaves_configs_reverted(self, installed):
        installed.cli.run("start-proxy", check=True)
        installed.cli.run("enable-autostart", check=True)

        installed.uninstall()

        for path in installed.device.residue_paths:
            assert not installed.container.exists(path), "residue left at %s" % path

        cfg = installed.container.read_file(installed.device.retroarch_cfg)
        assert cfg_value(cfg, HARDCORE_KEY) == "true"
        assert cfg_value(cfg, CUSTOM_HOST_KEY) == ""

        conf = installed.container.read_file(installed.device.batocera_conf)
        assert conf_value(conf, "global.retroachievements.hardcore") == "1"
