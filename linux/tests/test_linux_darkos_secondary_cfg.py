import tempfile
import unittest
from pathlib import Path
from unittest import mock

from linux.raofflineproxy import retroarch_cfg

PROXY = {"proxy_host": "127.0.0.1", "proxy_port": 8080}


class DarkosSecondaryCfgTests(unittest.TestCase):
    def _cfg(self, temp_dir: str, body: str) -> Path:
        cfg = Path(temp_dir) / "retroarch32.cfg"
        cfg.write_text(body, encoding="utf-8")
        return cfg

    def _patch(self, cfg: Path, saved: list[dict] | None = None) -> dict:
        with mock.patch.object(
            retroarch_cfg, "secondary_retroarch_cfgs", return_value=[str(cfg)]
        ):
            return retroarch_cfg.patch_secondary_retroarch_cfgs(PROXY, saved)

    def _revert(self, cfg: Path, saved: list[dict]) -> dict:
        with mock.patch.object(
            retroarch_cfg, "secondary_retroarch_cfgs", return_value=[str(cfg)]
        ):
            return retroarch_cfg.revert_secondary_retroarch_cfgs(PROXY, saved)

    def test_the_32_bit_config_gets_the_proxy_host(self) -> None:
        # Without this every core EmulationStation runs under the 32-bit build
        # talks to retroachievements.org directly and bypasses the proxy.
        with tempfile.TemporaryDirectory() as temp_dir:
            cfg = self._cfg(temp_dir, 'cheevos_enable = "false"\n')

            result = self._patch(cfg)

            content = cfg.read_text(encoding="utf-8")
            self.assertIn('cheevos_custom_host = "127.0.0.1:8080"', content)
            self.assertIn('cheevos_enable = "true"', content)
            self.assertTrue(result["entries"][0]["changed"])

    def test_patch_then_revert_restores_the_original(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            original = 'cheevos_custom_host = "retroachievements.org"\ncheevos_enable = "true"\n'
            cfg = self._cfg(temp_dir, original)

            patch_state: dict = {}
            retroarch_cfg.store_secondary_retroarch_previous(
                patch_state, self._patch(cfg)
            )
            self._revert(cfg, patch_state["secondary_cfgs"])

            content = cfg.read_text(encoding="utf-8")
            self.assertIn('cheevos_custom_host = "retroachievements.org"', content)
            self.assertIn('cheevos_enable = "true"', content)

    def test_repatching_does_not_overwrite_the_saved_original(self) -> None:
        # The second patch sees the proxy's own host in the file; recording that
        # as "previous" would make revert restore the proxy address forever.
        with tempfile.TemporaryDirectory() as temp_dir:
            cfg = self._cfg(
                temp_dir, 'cheevos_custom_host = "retroachievements.org"\n'
            )

            patch_state: dict = {}
            retroarch_cfg.store_secondary_retroarch_previous(
                patch_state, self._patch(cfg)
            )
            retroarch_cfg.store_secondary_retroarch_previous(
                patch_state, self._patch(cfg, patch_state["secondary_cfgs"])
            )

            self.assertEqual(
                patch_state["secondary_cfgs"][0]["previous_host"],
                "retroachievements.org",
            )

    def test_revert_clears_an_unrecorded_config(self) -> None:
        # Upgrade case: a build that predates secondary state left the proxy host
        # in the file. Leaving it there breaks achievements once we stop.
        with tempfile.TemporaryDirectory() as temp_dir:
            cfg = self._cfg(temp_dir, 'cheevos_custom_host = "127.0.0.1:8080"\n')

            self._revert(cfg, [])

            self.assertNotIn("127.0.0.1:8080", cfg.read_text(encoding="utf-8"))

    def test_a_missing_config_is_skipped(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            missing = Path(temp_dir) / "absent.cfg"

            self.assertEqual(self._patch(missing)["entries"], [])
            self.assertEqual(self._revert(missing, [])["reverted"], [])

    def test_no_secondary_configs_off_darkos(self) -> None:
        with mock.patch.object(
            retroarch_cfg, "detect_darkos_retroarch32_cfg", return_value=None
        ):
            self.assertEqual(retroarch_cfg.secondary_retroarch_cfgs(PROXY), [])


if __name__ == "__main__":
    unittest.main()
