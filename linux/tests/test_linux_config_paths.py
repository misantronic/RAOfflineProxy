import os
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from linux.raofflineproxy import config


class LinuxConfigPathTests(unittest.TestCase):
    def test_resolve_config_dir_prefers_explicit_override(self) -> None:
        original = os.environ.get("RAOFFLINEPROXY_CONFIG_DIR")
        try:
            os.environ["RAOFFLINEPROXY_CONFIG_DIR"] = "/tmp/raofflineproxy-test"
            self.assertEqual(
                config.resolve_config_dir(),
                Path("/tmp/raofflineproxy-test"),
            )
        finally:
            if original is None:
                os.environ.pop("RAOFFLINEPROXY_CONFIG_DIR", None)
            else:
                os.environ["RAOFFLINEPROXY_CONFIG_DIR"] = original

    def test_resolve_config_dir_uses_xdg_config_home(self) -> None:
        original_override = os.environ.get("RAOFFLINEPROXY_CONFIG_DIR")
        original_xdg = os.environ.get("XDG_CONFIG_HOME")
        try:
            os.environ.pop("RAOFFLINEPROXY_CONFIG_DIR", None)
            os.environ["XDG_CONFIG_HOME"] = "/tmp/xdg-config"
            self.assertEqual(
                config.resolve_config_dir(),
                Path("/tmp/xdg-config/raofflineproxy"),
            )
        finally:
            if original_override is None:
                os.environ.pop("RAOFFLINEPROXY_CONFIG_DIR", None)
            else:
                os.environ["RAOFFLINEPROXY_CONFIG_DIR"] = original_override
            if original_xdg is None:
                os.environ.pop("XDG_CONFIG_HOME", None)
            else:
                os.environ["XDG_CONFIG_HOME"] = original_xdg

    def test_resolve_config_dir_uses_muos_application_dir(self) -> None:
        original_override = os.environ.get("RAOFFLINEPROXY_CONFIG_DIR")
        original_xdg = os.environ.get("XDG_CONFIG_HOME")
        original_onion = config.DEFAULT_ONION_APP_DIR
        original_muos = config.DEFAULT_MUOS_APPLICATION_DIR
        try:
            with tempfile.TemporaryDirectory() as temp_dir:
                os.environ.pop("RAOFFLINEPROXY_CONFIG_DIR", None)
                os.environ.pop("XDG_CONFIG_HOME", None)
                config.DEFAULT_ONION_APP_DIR = Path(temp_dir) / "missing-onion"
                config.DEFAULT_MUOS_APPLICATION_DIR = Path(temp_dir) / "RAOfflineProxy"
                config.DEFAULT_MUOS_APPLICATION_DIR.mkdir(parents=True)

                self.assertEqual(
                    config.resolve_config_dir(),
                    config.DEFAULT_MUOS_APPLICATION_DIR / "data",
                )
        finally:
            config.DEFAULT_ONION_APP_DIR = original_onion
            config.DEFAULT_MUOS_APPLICATION_DIR = original_muos
            if original_override is None:
                os.environ.pop("RAOFFLINEPROXY_CONFIG_DIR", None)
            else:
                os.environ["RAOFFLINEPROXY_CONFIG_DIR"] = original_override
            if original_xdg is None:
                os.environ.pop("XDG_CONFIG_HOME", None)
            else:
                os.environ["XDG_CONFIG_HOME"] = original_xdg

    def test_resolve_config_dir_uses_darkos_home(self) -> None:
        original_override = os.environ.get("RAOFFLINEPROXY_CONFIG_DIR")
        original_xdg = os.environ.get("XDG_CONFIG_HOME")
        original_onion = config.DEFAULT_ONION_APP_DIR
        original_muos = config.DEFAULT_MUOS_APPLICATION_DIR
        original_darkos = config.DEFAULT_DARKOS_HOME
        try:
            with tempfile.TemporaryDirectory() as temp_dir:
                os.environ.pop("RAOFFLINEPROXY_CONFIG_DIR", None)
                os.environ.pop("XDG_CONFIG_HOME", None)
                config.DEFAULT_ONION_APP_DIR = Path(temp_dir) / "missing-onion"
                config.DEFAULT_MUOS_APPLICATION_DIR = Path(temp_dir) / "missing-muos"
                config.DEFAULT_DARKOS_HOME = Path(temp_dir) / "ark"
                config.DEFAULT_DARKOS_HOME.mkdir(parents=True)

                self.assertEqual(
                    config.resolve_config_dir(),
                    config.DEFAULT_DARKOS_HOME / ".config" / "raofflineproxy",
                )
        finally:
            config.DEFAULT_ONION_APP_DIR = original_onion
            config.DEFAULT_MUOS_APPLICATION_DIR = original_muos
            config.DEFAULT_DARKOS_HOME = original_darkos
            if original_override is None:
                os.environ.pop("RAOFFLINEPROXY_CONFIG_DIR", None)
            else:
                os.environ["RAOFFLINEPROXY_CONFIG_DIR"] = original_override
            if original_xdg is None:
                os.environ.pop("XDG_CONFIG_HOME", None)
            else:
                os.environ["XDG_CONFIG_HOME"] = original_xdg

    def test_detect_retroarch_cfg_prefers_muos_path(self) -> None:
        original_override = os.environ.get("RAOFFLINEPROXY_RETROARCH_CFG")
        original_muos = config.DEFAULT_MUOS_RETROARCH_CFG
        try:
            with tempfile.TemporaryDirectory() as temp_dir:
                cfg_path = Path(temp_dir) / "retroarch.cfg"
                cfg_path.write_text("", encoding="utf-8")
                os.environ.pop("RAOFFLINEPROXY_RETROARCH_CFG", None)
                config.DEFAULT_MUOS_RETROARCH_CFG = cfg_path

                self.assertEqual(config.detect_retroarch_cfg(), str(cfg_path))
        finally:
            config.DEFAULT_MUOS_RETROARCH_CFG = original_muos
            if original_override is None:
                os.environ.pop("RAOFFLINEPROXY_RETROARCH_CFG", None)
            else:
                os.environ["RAOFFLINEPROXY_RETROARCH_CFG"] = original_override

    def test_detect_batocera_conf_returns_none_on_muos(self) -> None:
        original_override = os.environ.get("RAOFFLINEPROXY_BATOCERA_CONF")
        original_knulli = config.DEFAULT_KNULLI_CONF
        original_batocera = config.DEFAULT_BATOCERA_CONF
        try:
            with tempfile.TemporaryDirectory() as temp_dir:
                archive_dir = Path(temp_dir) / "opt" / "muos" / "script" / "archive"
                archive_dir.mkdir(parents=True)
                config.DEFAULT_KNULLI_CONF = Path(temp_dir) / "knulli.conf"
                config.DEFAULT_BATOCERA_CONF = Path(temp_dir) / "batocera.conf"
                os.environ.pop("RAOFFLINEPROXY_BATOCERA_CONF", None)
                original_exists = Path.exists

                def fake_exists(path: Path) -> bool:
                    if path == Path("/opt/muos/script/archive"):
                        return True
                    return original_exists(path)

                with mock.patch.object(Path, "exists", fake_exists):
                    self.assertIsNone(config.detect_batocera_conf({}))
        finally:
            config.DEFAULT_KNULLI_CONF = original_knulli
            config.DEFAULT_BATOCERA_CONF = original_batocera
            if original_override is None:
                os.environ.pop("RAOFFLINEPROXY_BATOCERA_CONF", None)
            else:
                os.environ["RAOFFLINEPROXY_BATOCERA_CONF"] = original_override


if __name__ == "__main__":
    unittest.main()
