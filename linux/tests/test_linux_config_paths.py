import os
import unittest
from pathlib import Path

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


if __name__ == "__main__":
    unittest.main()
