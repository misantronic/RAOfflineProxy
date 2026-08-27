import tempfile
import unittest
from pathlib import Path
from unittest import mock

from linux.raofflineproxy import lowerdeck


class LinuxLowerdeckTests(unittest.TestCase):
    def _flag_file(self, root: Path, content: str) -> Path:
        flag = root / "080-dual_screen_mode"
        flag.write_text(content, encoding="utf-8")
        return flag

    def test_is_dual_screen_true_when_flag_set(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            flag = self._flag_file(Path(temp_dir), "DEVICE_HAS_DUAL_SCREEN=true\n")
            with mock.patch.object(lowerdeck, "DUAL_SCREEN_FLAG_FILE", flag):
                self.assertTrue(lowerdeck.is_dual_screen())

    def test_is_dual_screen_false_for_single_screen_device(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            flag = self._flag_file(Path(temp_dir), "DEVICE_HAS_DUAL_SCREEN=false\n")
            with mock.patch.object(lowerdeck, "DUAL_SCREEN_FLAG_FILE", flag):
                self.assertFalse(lowerdeck.is_dual_screen())

    def test_is_dual_screen_false_when_flag_file_missing(self) -> None:
        missing = Path("/nonexistent/080-dual_screen_mode")
        with mock.patch.object(lowerdeck, "DUAL_SCREEN_FLAG_FILE", missing):
            self.assertFalse(lowerdeck.is_dual_screen())

    def test_command_points_upstream_at_our_proxy_and_disables_watchdog(self) -> None:
        command = lowerdeck.build_ra_proxy_command({"proxy_port": 8080})

        self.assertIn("--upstream", command)
        self.assertEqual(command[command.index("--upstream") + 1], "http://127.0.0.1:8080")
        # Empty needle is what disables their RetroArch watchdog; without it the
        # proxy shuts itself down and ROCKNIX restarts it pointed at RA directly.
        self.assertEqual(command[command.index("--retroarch-process-match") + 1], "")

    def test_command_honours_a_custom_proxy_port(self) -> None:
        command = lowerdeck.build_ra_proxy_command({"proxy_port": 9999})

        self.assertEqual(command[command.index("--upstream") + 1], "http://127.0.0.1:9999")

    def test_does_not_start_on_single_screen_devices(self) -> None:
        with mock.patch.object(lowerdeck, "should_chain_ra_proxy", return_value=False):
            with mock.patch.object(lowerdeck.subprocess, "Popen") as popen:
                self.assertFalse(lowerdeck.ensure_ra_proxy_chained({}))
                popen.assert_not_called()

    def test_does_not_start_a_second_instance(self) -> None:
        with mock.patch.object(lowerdeck, "should_chain_ra_proxy", return_value=True):
            with mock.patch.object(lowerdeck, "ra_proxy_running", return_value=True):
                with mock.patch.object(lowerdeck.subprocess, "Popen") as popen:
                    self.assertFalse(lowerdeck.ensure_ra_proxy_chained({}))
                    popen.assert_not_called()

    def test_starts_chained_proxy_when_none_is_running(self) -> None:
        with mock.patch.object(lowerdeck, "should_chain_ra_proxy", return_value=True):
            with mock.patch.object(lowerdeck, "ra_proxy_running", return_value=False):
                with mock.patch.object(lowerdeck.subprocess, "Popen") as popen:
                    self.assertTrue(lowerdeck.ensure_ra_proxy_chained({"proxy_port": 8080}))
                    popen.assert_called_once()
                    command = popen.call_args[0][0]
                    self.assertEqual(command[command.index("--upstream") + 1], "http://127.0.0.1:8080")

    def test_stop_terminates_the_proxy_we_started(self) -> None:
        process = mock.Mock()
        process.poll.return_value = None
        with mock.patch.object(lowerdeck, "_chained_process", process):
            self.assertTrue(lowerdeck.stop_ra_proxy_chain())
            process.terminate.assert_called_once()

    def test_stop_kills_a_proxy_that_ignores_terminate(self) -> None:
        process = mock.Mock()
        process.poll.return_value = None
        process.wait.side_effect = lowerdeck.subprocess.TimeoutExpired("ra_proxy", 5)
        with mock.patch.object(lowerdeck, "_chained_process", process):
            lowerdeck.stop_ra_proxy_chain()
            process.kill.assert_called_once()

    def test_stop_is_a_noop_when_we_started_nothing(self) -> None:
        with mock.patch.object(lowerdeck, "_chained_process", None):
            self.assertFalse(lowerdeck.stop_ra_proxy_chain())

    def test_stop_does_not_touch_a_proxy_that_already_exited(self) -> None:
        process = mock.Mock()
        process.poll.return_value = 0
        with mock.patch.object(lowerdeck, "_chained_process", process):
            self.assertFalse(lowerdeck.stop_ra_proxy_chain())
            process.terminate.assert_not_called()

    def test_start_failure_is_not_fatal(self) -> None:
        with mock.patch.object(lowerdeck, "should_chain_ra_proxy", return_value=True):
            with mock.patch.object(lowerdeck, "ra_proxy_running", return_value=False):
                with mock.patch.object(lowerdeck.subprocess, "Popen", side_effect=OSError("boom")):
                    self.assertFalse(lowerdeck.ensure_ra_proxy_chained({}))


if __name__ == "__main__":
    unittest.main()
