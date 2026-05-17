import tempfile
import unittest
from pathlib import Path

from linux.raofflineproxy import service
from linux.raofflineproxy import state


class LinuxServiceReconciliationTests(unittest.TestCase):
    def test_discover_service_pid_parses_matching_process(self) -> None:
        original_check_output = service.subprocess.check_output
        try:
            service.subprocess.check_output = lambda *_args, **_kwargs: (
                "123 /usr/bin/python3 -m something.else\n"
                "456 /usr/bin/python3 -m raofflineproxy.main run-service\n"
            )

            self.assertEqual(service.discover_service_pid(), 456)
        finally:
            service.subprocess.check_output = original_check_output

    def test_service_status_adopts_discovered_process_when_pid_file_is_missing(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            pid_path = Path(temp_dir) / "service.pid"
            status_path = Path(temp_dir) / "service_status.json"

            original_load_pid = service.load_pid
            original_discover = service.discover_service_pid
            original_process_is_running = service.process_is_running
            original_pid_file = state.PID_FILE
            original_status_file = state.STATUS_FILE
            try:
                state.PID_FILE = pid_path
                state.STATUS_FILE = status_path
                service.load_pid = lambda: None
                service.discover_service_pid = lambda: 3282
                service.process_is_running = lambda pid: pid == 3282

                status = service.service_status()

                self.assertTrue(status["running"])
                self.assertEqual(status["pid"], 3282)
                self.assertEqual(pid_path.read_text(encoding="utf-8").strip(), "3282")
            finally:
                service.load_pid = original_load_pid
                service.discover_service_pid = original_discover
                service.process_is_running = original_process_is_running
                state.PID_FILE = original_pid_file
                state.STATUS_FILE = original_status_file

    def test_process_has_exited_falls_back_to_process_signal_check(self) -> None:
        original_proc_state = service._proc_state
        original_process_is_running = service.process_is_running
        try:
            service._proc_state = lambda _pid: None
            service.process_is_running = lambda pid: pid == 123

            self.assertFalse(service.process_has_exited(123))
            self.assertTrue(service.process_has_exited(456))
        finally:
            service._proc_state = original_proc_state
            service.process_is_running = original_process_is_running


if __name__ == "__main__":
    unittest.main()
