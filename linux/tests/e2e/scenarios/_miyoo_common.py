from __future__ import annotations

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


class MiyooLifecycle:
    """Shared coverage for the three firmwares on Miyoo Mini hardware.

    Subclassed per device so pytest collects one copy each; the `installed`
    fixture comes from the importing module.
    """

    def test_zip_unpacks_onto_the_card(self, installed):
        base = installed.device.base_dir
        for entry in ("launch.sh", "common.sh", "app", "lib", "runtime"):
            assert installed.container.exists("%s/%s" % (base, entry))

    def test_bundled_armv7_runtime_is_the_one_that_runs(self, installed):
        """The bundle ships its own CPython 3.9; common.sh must prefer it over
        whatever python the firmware happens to have."""
        result = installed.container.exec(
            "cd %s && . ./common.sh && prepare_env >/dev/null 2>&1 && "
            'resolve_python_bin && echo "$RESOLVED_PYTHON_BIN" && '
            '"$RESOLVED_PYTHON_BIN" -V' % installed.device.base_dir,
            check=True,
        )
        lines = result.stdout.strip().splitlines()
        assert lines[0] == installed.device.base_dir + "/runtime/bin/python3"
        assert lines[1].startswith("Python 3.9")

    def test_architecture_is_armv7(self, installed):
        assert installed.container.exec("uname -m", check=True).stdout.strip() == "armv7l"

    def test_native_hasher_loads_and_hashes_on_armv7(self, installed):
        result = installed.cli.run(
            "cached-games-count"
        )  # warm the runtime before the hash call
        assert result.ok
        result = installed.container.exec(
            "cd %s && . ./common.sh && prepare_env >/dev/null 2>&1 && "
            'resolve_python_bin && "$RESOLVED_PYTHON_BIN" -c '
            "'from raofflineproxy import rom_hashing; "
            "assert rom_hashing.load_rchash() is not None; "
            'print(rom_hashing.hash_rom("%s"))\'' % (installed.device.base_dir, installed.rom),
            check=True,
        )
        assert result.stdout.strip().splitlines()[-1] == MSLUG_HASH

    def test_common_sh_exports_the_bundled_environment(self, installed):
        result = installed.container.exec(
            "cd %s && . ./common.sh && prepare_env >/dev/null 2>&1 && "
            'printf "%%s\\n%%s\\n%%s\\n" "$RAOFFLINEPROXY_CONFIG_DIR" '
            '"$RAOFFLINEPROXY_RETROARCH_CFG" "$SDL_VIDEODRIVER"'
            % installed.device.base_dir,
            check=True,
        )
        config_dir, retroarch_cfg, video_driver = result.stdout.strip().splitlines()[:3]
        assert config_dir == installed.device.config_dir
        assert retroarch_cfg == installed.device.retroarch_cfg
        assert video_driver == "Mini"

    def test_start_proxy_patches_retroarch(self, installed):
        result = installed.cli.run("start-proxy", check=True)
        assert "Patched retroarch.cfg" in result.stdout
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

    def test_enable_and_disable_autostart(self, installed):
        installed.cli.run("enable-autostart", check=True)
        assert installed.cli.autostart_enabled()
        assert installed.container.exists(installed.device.boot_hook)

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

    def test_no_in_app_uninstaller_and_manual_removal_leaves_config_clean(self, installed):
        """The Miyoo firmwares have no uninstall entry point — the menu reports
        it is unavailable and users delete the folder from the card."""
        assert installed.device.uninstall_path is None

        installed.cli.run("start-proxy", check=True)
        installed.cli.run("enable-autostart", check=True)
        installed.cli.run("stop-proxy", check=True)
        installed.cli.run("remove-boot-hook", check=True)

        cfg = installed.container.read_file(installed.device.retroarch_cfg)
        assert cfg_value(cfg, HARDCORE_KEY) == "true"
        assert cfg_value(cfg, CUSTOM_HOST_KEY) == ""

        installed.container.exec("rm -rf %s" % installed.device.base_dir, check=True)
        assert not installed.container.exists(installed.device.base_dir)
