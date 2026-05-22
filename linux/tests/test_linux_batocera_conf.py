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


if __name__ == "__main__":
    unittest.main()
