import tempfile
import unittest
from pathlib import Path

from linux.raofflineproxy import batocera_conf, config


class LinuxBatoceraConfTests(unittest.TestCase):
    def test_detect_batocera_conf_prefers_knulli_conf(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            original_knulli = config.DEFAULT_KNULLI_CONF
            original_batocera = config.DEFAULT_BATOCERA_CONF
            try:
                knulli_conf = Path(temp_dir) / "knulli.conf"
                batocera_conf_path = Path(temp_dir) / "batocera.conf"
                knulli_conf.write_text("", encoding="utf-8")
                batocera_conf_path.write_text("", encoding="utf-8")

                config.DEFAULT_KNULLI_CONF = knulli_conf
                config.DEFAULT_BATOCERA_CONF = batocera_conf_path

                self.assertEqual(config.detect_batocera_conf({}), str(knulli_conf))
            finally:
                config.DEFAULT_KNULLI_CONF = original_knulli
                config.DEFAULT_BATOCERA_CONF = original_batocera

    def test_build_patched_batocera_conf_enables_cheevos_keys(
        self,
    ) -> None:
        content = "global.retroachievements=1\n"

        result = batocera_conf.build_patched_batocera_conf(content, {})

        self.assertIn("global.retroachievements=1\n", result)
        self.assertIn('global.retroarch.cheevos_enable="true"\n', result)
        self.assertIn('global.retroarch.cheevos_custom_host="127.0.0.1:8080"\n', result)

    def test_build_reverted_batocera_conf_restores_previous_values(self) -> None:
        content = (
            "global.retroachievements=1\n"
            'global.retroarch.cheevos_enable="true"\n'
            'global.retroarch.cheevos_custom_host="127.0.0.1:8080"\n'
            'global.retroarch.cheevos_hardcore_mode_enable="false"\n'
        )

        result = batocera_conf.build_reverted_batocera_conf(
            content,
            {
                batocera_conf.RETROACHIEVEMENTS_KEY: "1",
                batocera_conf.RETROACHIEVEMENTS_HARDCORE_KEY: "0",
                batocera_conf.RETROARCH_CHEEVOS_ENABLE_KEY: '"true"',
                batocera_conf.CHEEVOS_CUSTOM_HOST_KEY: None,
                batocera_conf.CHEEVOS_HARDCORE_KEY: '"false"',
            },
        )

        self.assertIn("global.retroachievements=1\n", result)
        self.assertNotIn(
            'global.retroarch.cheevos_custom_host="127.0.0.1:8080"', result
        )

    def test_revert_strips_custom_host_when_previous_clobbered_with_proxy(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            original_knulli = config.DEFAULT_KNULLI_CONF
            try:
                knulli_conf = Path(temp_dir) / "knulli.conf"
                knulli_conf.write_text(
                    "global.retroachievements=1\n"
                    'global.retroarch.cheevos_custom_host="127.0.0.1:8080"\n',
                    encoding="utf-8",
                )
                config.DEFAULT_KNULLI_CONF = knulli_conf

                # A re-patch captured the proxy host itself as the "previous" value.
                clobbered_previous = {
                    batocera_conf.CHEEVOS_CUSTOM_HOST_KEY: '"127.0.0.1:8080"'
                }

                batocera_conf.revert_batocera_conf({}, clobbered_previous)

                self.assertNotIn(
                    "cheevos_custom_host",
                    knulli_conf.read_text(encoding="utf-8"),
                )
            finally:
                config.DEFAULT_KNULLI_CONF = original_knulli

    def test_store_batocera_previous_preserves_original_on_repatch(self) -> None:
        patch_state: dict = {}

        first = {
            "already_patched": False,
            "path": "/userdata/system/knulli.conf",
            "previous": {batocera_conf.CHEEVOS_CUSTOM_HOST_KEY: None},
        }
        batocera_conf.store_batocera_previous(patch_state, first)

        repatch = {
            "already_patched": True,
            "path": "/userdata/system/knulli.conf",
            "previous": {batocera_conf.CHEEVOS_CUSTOM_HOST_KEY: '"127.0.0.1:8080"'},
        }
        batocera_conf.store_batocera_previous(patch_state, repatch)

        self.assertIsNone(
            patch_state["batocera_previous"][batocera_conf.CHEEVOS_CUSTOM_HOST_KEY]
        )


if __name__ == "__main__":
    unittest.main()


class RevertNeverDisablesRetroachievementsTests(unittest.TestCase):
    def test_revert_keeps_master_toggle_enabled_when_previous_was_absent(self) -> None:
        content = (
            "global.retroachievements=1\n"
            'global.retroarch.cheevos_custom_host="127.0.0.1:8080"\n'
        )
        previous = {
            batocera_conf.RETROACHIEVEMENTS_KEY: None,
            batocera_conf.CHEEVOS_CUSTOM_HOST_KEY: None,
        }
        result = batocera_conf.build_reverted_batocera_conf(content, previous)
        self.assertIn("global.retroachievements=1\n", result)
        self.assertNotIn("cheevos_custom_host", result)

    def test_revert_keeps_master_toggle_enabled_when_previous_was_disabled(self) -> None:
        content = "global.retroachievements=1\n"
        previous = {batocera_conf.RETROACHIEVEMENTS_KEY: "0"}
        result = batocera_conf.build_reverted_batocera_conf(content, previous)
        self.assertIn("global.retroachievements=1\n", result)
        self.assertNotIn("global.retroachievements=0", result)


class LinuxRocknixSystemConfAsBatoceraConfTests(unittest.TestCase):
    """ROCKNIX has no batocera.conf/knulli.conf, but its system.cfg carries the same
    global.retroachievements keys. Without routing it through this module hardcore
    stays on and RetroArch rejects every unlock with hardcore_not_supported."""

    def test_detect_batocera_conf_returns_rocknix_system_cfg(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            system_cfg = Path(temp_dir) / "system.cfg"
            system_cfg.write_text(
                "global.retroachievements=1\nglobal.retroachievements.hardcore=1\n",
                encoding="utf-8",
            )

            self.assertEqual(
                config.detect_batocera_conf({"rocknix_system_cfg": str(system_cfg)}),
                str(system_cfg),
            )

    def test_patch_disables_hardcore_and_revert_restores_it(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            system_cfg = Path(temp_dir) / "system.cfg"
            system_cfg.write_text(
                "global.retroachievements=1\nglobal.retroachievements.hardcore=1\n",
                encoding="utf-8",
            )
            config_data = {"rocknix_system_cfg": str(system_cfg)}

            patched = batocera_conf.patch_batocera_conf(config_data)
            self.assertTrue(patched["exists"])
            self.assertIn(
                "global.retroachievements.hardcore=0",
                system_cfg.read_text(encoding="utf-8"),
            )

            batocera_conf.revert_batocera_conf(config_data, patched["previous"])
            self.assertIn(
                "global.retroachievements.hardcore=1",
                system_cfg.read_text(encoding="utf-8"),
            )

    def test_revert_never_disables_the_master_toggle(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            system_cfg = Path(temp_dir) / "system.cfg"
            system_cfg.write_text("global.retroachievements.hardcore=1\n", encoding="utf-8")
            config_data = {"rocknix_system_cfg": str(system_cfg)}

            patched = batocera_conf.patch_batocera_conf(config_data)
            batocera_conf.revert_batocera_conf(config_data, patched["previous"])

            self.assertIn(
                "global.retroachievements=1", system_cfg.read_text(encoding="utf-8")
            )
