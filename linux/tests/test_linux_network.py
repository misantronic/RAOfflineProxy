import unittest
import urllib.error
import logging
import os
from email.message import Message

from linux.raofflineproxy import config
from linux.raofflineproxy import network


class LinuxNetworkTests(unittest.TestCase):
    def tearDown(self) -> None:
        network.reset_retroachievements_reachability_for_tests()
        network.reset_request_throttle_for_tests()
        os.environ.pop("RAOFFLINEPROXY_CA_FILE", None)
        os.environ.pop("SSL_CERT_FILE", None)

    def test_redacted_url_hides_sensitive_query_values(self) -> None:
        url = "https://retroachievements.org/dorequest.php?r=login2&u=user&p=password&t=secret"

        self.assertEqual(
            network.redacted_url(url),
            "https://retroachievements.org/dorequest.php?r=login2&u=user&p=%3Credacted%3E&t=%3Credacted%3E",
        )

    def test_http_get_logs_connection_errors_with_redacted_url(self) -> None:
        original_urlopen = network.urllib.request.urlopen
        try:
            network.urllib.request.urlopen = lambda _request, timeout=0, context=None: (
                _ for _ in ()
            ).throw(urllib.error.URLError("offline"))

            with self.assertLogs("raofflineproxy", level="WARNING") as logs:
                with self.assertRaises(urllib.error.URLError):
                    network.http_get(
                        "https://retroachievements.org/dorequest.php?r=patch&t=secret",
                        "RetroArch/1.20.0",
                    )

            output = "\n".join(logs.output)
            self.assertIn("GET connection failed", output)
            self.assertIn("t=%3Credacted%3E", output)
            self.assertNotIn("secret", output)
        finally:
            network.urllib.request.urlopen = original_urlopen

    def test_configure_logging_writes_to_service_log(self) -> None:
        original_log_file = config.LOG_FILE
        try:
            config.LOG_FILE = config.CONFIG_DIR / "test-service.log"
            if config.LOG_FILE.exists():
                config.LOG_FILE.unlink()

            config.configure_logging()
            logging.getLogger("raofflineproxy").warning("test log entry")
            logging.shutdown()

            self.assertIn(
                "test log entry",
                config.LOG_FILE.read_text(encoding="utf-8"),
            )
        finally:
            if config.LOG_FILE.exists():
                config.LOG_FILE.unlink()
            config.LOG_FILE = original_log_file

    def test_should_probe_retroachievements_false_for_recent_success(self) -> None:
        network.mark_retroachievements_reachable(checked_at=10.0)

        self.assertFalse(network.should_probe_retroachievements(now=20.0))

    def test_should_probe_retroachievements_true_after_interval(self) -> None:
        network.mark_retroachievements_reachable(checked_at=10.0)

        self.assertTrue(network.should_probe_retroachievements(now=50.0))

    def test_probe_retroachievements_returns_cached_success_without_network_call(
        self,
    ) -> None:
        original_urlopen = network.urllib.request.urlopen
        try:
            network.mark_retroachievements_reachable(checked_at=10.0)

            def fail_urlopen(_request, timeout=0, context=None):
                raise AssertionError("probe should not hit the network")

            network.urllib.request.urlopen = fail_urlopen

            self.assertTrue(network.probe_retroachievements({}, force=False, now=20.0))
        finally:
            network.urllib.request.urlopen = original_urlopen

    def test_http_get_marks_retroachievements_unreachable_on_connection_error(
        self,
    ) -> None:
        original_urlopen = network.urllib.request.urlopen
        try:
            network.urllib.request.urlopen = lambda _request, timeout=0, context=None: (
                _ for _ in ()
            ).throw(urllib.error.URLError("offline"))

            with self.assertRaises(urllib.error.URLError):
                network.http_get(
                    "https://retroachievements.org/dorequest.php?r=patch&t=secret",
                    "RetroArch/1.20.0",
                )

            self.assertFalse(network.is_retroachievements_reachable())
        finally:
            network.urllib.request.urlopen = original_urlopen

    def test_http_get_retries_429_then_succeeds(self) -> None:
        original_urlopen = network.urllib.request.urlopen
        original_sleep = network.time.sleep
        original_wait = network._request_throttle.wait
        attempts = []
        sleeps = []

        class FakeResponse:
            headers = {"Content-Type": "application/json"}

            def __enter__(self):
                return self

            def __exit__(self, exc_type, exc, tb):
                return False

            def read(self):
                return b'{"Success":true}'

        try:

            def fake_urlopen(_request, timeout=0, context=None):
                attempts.append(timeout)
                if len(attempts) == 1:
                    headers = Message()
                    headers["Retry-After"] = "2"
                    raise urllib.error.HTTPError(
                        url="https://retroachievements.org/dorequest.php?r=patch",
                        code=429,
                        msg="Too Many Requests",
                        hdrs=headers,
                        fp=None,
                    )
                return FakeResponse()

            network.urllib.request.urlopen = fake_urlopen
            network.time.sleep = lambda seconds: sleeps.append(seconds)
            network._request_throttle.wait = lambda action=None: None

            body = network.http_get(
                "https://retroachievements.org/dorequest.php?r=patch&t=secret",
                "RetroArch/1.20.0",
            )

            self.assertEqual(body, '{"Success":true}')
            self.assertEqual(len(attempts), 2)
            self.assertEqual(sleeps, [2.0])
        finally:
            network.urllib.request.urlopen = original_urlopen
            network.time.sleep = original_sleep
            network._request_throttle.wait = original_wait

    def test_configured_ssl_context_uses_explicit_ca_file(self) -> None:
        os.environ["RAOFFLINEPROXY_CA_FILE"] = "/tmp/test-ca.pem"

        original_create_default_context = network.ssl.create_default_context
        captured = {}
        try:

            def fake_create_default_context(*, cafile=None, capath=None):
                captured["cafile"] = cafile
                captured["capath"] = capath
                return object()

            network.ssl.create_default_context = fake_create_default_context
            context = network.configured_ssl_context()

            self.assertIsNotNone(context)
            self.assertEqual(captured["cafile"], "/tmp/test-ca.pem")
            self.assertIsNone(captured["capath"])
        finally:
            network.ssl.create_default_context = original_create_default_context


if __name__ == "__main__":
    unittest.main()

