from __future__ import annotations

import pytest

USER = "testuser"
TOKEN = "tok-testuser-000000000001"
MSLUG_HASH = "b43c8b4ec999588c04dad79bb8bcc745"
MSLUG_GAME_ID = 1447

HARDCORE_KEY = "cheevos_hardcore_mode_enable"
CUSTOM_HOST_KEY = "cheevos_custom_host"
USER_INIT = "/opt/muos/config/settings/advanced/user_init"
THEME_ICON = "/run/muos/storage/theme/default/glyph/muxapp/raofflineproxy.png"


def cfg_value(content: str, key: str) -> str | None:
    for line in content.splitlines():
        stripped = line.strip()
        if stripped.startswith(key):
            return stripped.split("=", 1)[1].strip().strip('"')
    return None


@pytest.fixture
def installed(muos):
    muos.install()
    muos.rom = muos.stage_rom("mslug.7z")
    return muos


class TestInstall:
    def test_muxapp_unpacks_like_archive_manager(self, muos):
        muos.install()
        base = muos.device.base_dir
        for entry in ("launch.sh", "mux_launch.sh", "uninstall.sh", "app", "lib", "pygame"):
            assert muos.container.exists("%s/%s" % (base, entry))
        assert muos.container.is_executable(base + "/launch.sh")

    def test_launcher_uses_usr_bin_python(self, installed):
        """muOS's launch.sh calls /usr/bin/python, not python3."""
        assert installed.container.exists("/usr/bin/python")
        launcher = installed.container.read_file(installed.device.bin_path)
        assert "/usr/bin/python " in launcher or "/usr/bin/python -m" in launcher

    def test_config_dir_is_the_bundled_data_dir(self, installed):
        result = installed.cli.run("service-status", check=True)
        assert "running: no" in result.stdout
        assert installed.container.exists(installed.device.config_dir)

    def test_muos_has_no_batocera_conf(self, installed):
        """/opt/muos/script/archive makes detect_batocera_conf() return None."""
        result = installed.app_python(
            "from raofflineproxy import config;"
            "print(config.detect_batocera_conf({}))",
            check=True,
        )
        assert result.stdout.strip() == "None"

    def test_retroarch_cfg_resolves_to_the_muos_path(self, installed):
        result = installed.app_python(
            "from raofflineproxy import config;"
            "print(config.detect_retroarch_cfg())",
            check=True,
        )
        assert result.stdout.strip() == installed.device.retroarch_cfg

    def test_native_hasher_loads_and_hashes_on_aarch64(self, installed):
        result = installed.app_python(
            "from raofflineproxy import rom_hashing;"
            "assert rom_hashing.load_rchash() is not None;"
            "print(rom_hashing.hash_rom('%s'))" % installed.rom,
            check=True,
        )
        assert result.stdout.strip().splitlines()[-1] == MSLUG_HASH

    def test_vendored_pygame_matches_the_interpreter_abi(self, installed):
        version = installed.container.exec(
            "python3 -c 'import sys; print(\"%d%d\" % sys.version_info[:2])'",
            check=True,
        ).stdout.strip()
        assert installed.container.exists(
            "%s/pygame/base.cpython-%s-aarch64-linux-gnu.so"
            % (installed.device.base_dir, version)
        )
        result = installed.container.exec(
            "cd %s && PYTHONPATH=%s/app:%s python3 -c "
            "'import pygame; print(pygame.version.ver)'"
            % ((installed.device.base_dir,) * 3),
            check=True,
        )
        assert result.stdout.strip()


class TestProxyLifecycle:
    def test_start_proxy_patches_retroarch_only(self, installed):
        result = installed.cli.run("start-proxy", check=True)
        assert "Patched retroarch.cfg" in result.stdout
        assert "batocera" not in result.stdout
        assert installed.cli.service_running()

        cfg = installed.container.read_file(installed.device.retroarch_cfg)
        assert cfg_value(cfg, HARDCORE_KEY) == "false"
        assert cfg_value(cfg, CUSTOM_HOST_KEY) == installed.device.proxy_value

    def test_stop_proxy_reverts_and_restores_hardcore(self, installed):
        installed.cli.run("start-proxy", check=True)
        installed.cli.run("stop-proxy", check=True)
        assert not installed.cli.service_running()

        cfg = installed.container.read_file(installed.device.retroarch_cfg)
        assert cfg_value(cfg, HARDCORE_KEY) == "true"
        assert cfg_value(cfg, CUSTOM_HOST_KEY) == ""

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
    def test_enable_autostart_writes_init_script_and_user_init(self, installed):
        installed.cli.run("enable-autostart", check=True)
        assert installed.cli.autostart_enabled()
        assert installed.container.exists(installed.device.boot_hook)
        assert installed.container.is_executable(installed.device.boot_hook)
        # muOS only runs init/ scripts when this advanced setting is on.
        assert installed.container.read_file(USER_INIT).strip() == "1"

    def test_boot_hook_points_at_the_launcher(self, installed):
        installed.cli.run("enable-autostart", check=True)
        hook = installed.container.read_file(installed.device.boot_hook)
        assert installed.device.bin_path in hook

    def test_disable_autostart(self, installed):
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
    def test_uninstall_removes_the_app_and_leaves_config_reverted(self, installed):
        installed.cli.run("start-proxy", check=True)
        installed.cli.run("enable-autostart", check=True)

        installed.uninstall()

        for path in installed.device.residue_paths:
            assert not installed.container.exists(path), "residue left at %s" % path

        cfg = installed.container.read_file(installed.device.retroarch_cfg)
        assert cfg_value(cfg, HARDCORE_KEY) == "true"
        assert cfg_value(cfg, CUSTOM_HOST_KEY) == ""

    def test_uninstall_removes_the_theme_icon(self, installed):
        """Regression: uninstall.sh used `find -delete`, which BusyBox find does
        not implement, so the icons survived with the error swallowed."""
        installed.container.exec(
            "cp %s/raofflineproxy.png %s" % (installed.device.base_dir, THEME_ICON),
            check=True,
        )
        installed.uninstall()
        assert not installed.container.exists(THEME_ICON)
