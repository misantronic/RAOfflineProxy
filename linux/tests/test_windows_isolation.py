"""The Windows port must not change anything on the other devices: no code from
raofflineproxy.windows loads, the proxy's user agent still says Linux (the
RetroAchievements server gates on it), and emulator configs are written exactly
as before."""

import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

REPO_ROOT = Path(__file__).resolve().parents[2]

# Imports the shared modules as a Linux device would. sys.platform is set first,
# so on a Windows host this pretends to be Linux; on Linux it changes nothing.
OFF_WINDOWS_PROBE = """
import json, sys
sys.platform = "linux"
from linux.raofflineproxy import cache_queue, config, flusher, log_uploader, proxy_service, service, usage_report, utils, watch_folders
from linux.raofflineproxy.emulators import dolphin_cfg, ppsspp_cfg, retroarch_cfg
from linux.raofflineproxy import platform as rap_platform
config.resolve_config_dir()
ppsspp_cfg.detect_ppsspp_ini({})
dolphin_cfg.detect_dolphin_config_dir({})
log_uploader._log_file_paths()
print(json.dumps({
    "ua": config.PROXY_UA_TAG,
    "windows_modules": sorted(name for name in sys.modules if ".raofflineproxy.windows" in name),
    "usage_platform": usage_report.build_payload("user", {}, "release", {}, {})["platform"],
    "update_log_listed": any(path.name == "update.log" for path in log_uploader._log_file_paths()),
}))
"""


class OffWindowsTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        with tempfile.TemporaryDirectory() as config_dir:
            result = subprocess.run(
                [sys.executable, "-c", OFF_WINDOWS_PROBE],
                cwd=REPO_ROOT,
                env=dict(os.environ, RAOFFLINEPROXY_CONFIG_DIR=config_dir),
                capture_output=True,
                text=True,
                check=True,
            )
        cls.probe = json.loads(result.stdout.strip().splitlines()[-1])

    def test_user_agent_still_says_linux(self) -> None:
        self.assertRegex(self.probe["ua"], r"^RAOfflineProxy/Linux/")

    def test_no_windows_code_loads(self) -> None:
        self.assertEqual(self.probe["windows_modules"], [])

    def test_usage_reports_stay_linux(self) -> None:
        self.assertEqual(self.probe["usage_platform"], "linux")

    def test_send_logs_lists_no_windows_files(self) -> None:
        self.assertFalse(self.probe["update_log_listed"])


class EmulatorConfigWriteTests(unittest.TestCase):
    def test_off_windows_writes_exactly_as_write_text_did(self) -> None:
        from linux.raofflineproxy import utils

        content = 'cheevos_enable = "true"\nmenu_driver = "ozone"\n'
        with tempfile.TemporaryDirectory() as folder:
            target = Path(folder) / "retroarch.cfg"
            expected = Path(folder) / "expected.cfg"
            expected.write_text(content, encoding="utf-8")
            with mock.patch.object(utils, "running_on_windows", return_value=False):
                utils.write_emulator_config(target, content)
            self.assertEqual(target.read_bytes(), expected.read_bytes())


if __name__ == "__main__":
    unittest.main()
