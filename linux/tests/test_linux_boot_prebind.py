import os
import socket
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from linux.raofflineproxy import boot, proxy_service, storage


def _free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
        probe.bind(("127.0.0.1", 0))
        return probe.getsockname()[1]


class PrebindListenSocketTests(unittest.TestCase):
    def setUp(self) -> None:
        os.environ.pop(boot.LISTEN_FD_ENV, None)
        self.addCleanup(os.environ.pop, boot.LISTEN_FD_ENV, None)

    def test_prebound_socket_accepts_connections_before_the_service_runs(self) -> None:
        port = _free_port()
        with mock.patch(
            "linux.raofflineproxy.config.proxy_port", return_value=port
        ), mock.patch(
            "linux.raofflineproxy.config.proxy_host", return_value="127.0.0.1"
        ), mock.patch(
            "linux.raofflineproxy.config.load_config", return_value={}
        ):
            listen_socket = boot.prebind_listen_socket()

        self.assertIsNotNone(listen_socket)
        assert listen_socket is not None
        self.addCleanup(listen_socket.close)

        # Nothing is accepting yet — the connection must still be established
        # rather than refused, which is what keeps rcheevos from giving up.
        with socket.create_connection(("127.0.0.1", port), timeout=2) as client:
            connection, _ = listen_socket.accept()
            connection.close()
            self.assertIsNotNone(client)

    def test_prebind_returns_none_when_the_port_is_taken(self) -> None:
        port = _free_port()
        holder = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        holder.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        holder.bind(("127.0.0.1", port))
        holder.listen(1)
        self.addCleanup(holder.close)

        with mock.patch(
            "linux.raofflineproxy.config.proxy_port", return_value=port
        ), mock.patch(
            "linux.raofflineproxy.config.proxy_host", return_value="127.0.0.1"
        ), mock.patch(
            "linux.raofflineproxy.config.load_config", return_value={}
        ):
            self.assertIsNone(boot.prebind_listen_socket())

    def test_adopt_returns_the_same_socket_and_clears_the_environment(self) -> None:
        port = _free_port()
        listen_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        listen_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        listen_socket.bind(("127.0.0.1", port))
        listen_socket.listen(1)
        listen_socket.set_inheritable(True)
        self.addCleanup(listen_socket.close)

        os.environ[boot.LISTEN_FD_ENV] = str(listen_socket.fileno())
        adopted = boot.adopt_listen_socket()

        self.assertIsNotNone(adopted)
        assert adopted is not None
        self.addCleanup(adopted.detach)
        self.assertEqual(adopted.getsockname()[1], port)
        self.assertNotIn(boot.LISTEN_FD_ENV, os.environ)

    def test_adopt_without_a_handover_returns_none(self) -> None:
        self.assertIsNone(boot.adopt_listen_socket())

    def test_adopt_ignores_a_malformed_handover(self) -> None:
        os.environ[boot.LISTEN_FD_ENV] = "not-a-number"
        self.assertIsNone(boot.adopt_listen_socket())

    def test_server_serves_on_the_handed_over_socket(self) -> None:
        port = _free_port()
        listen_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        listen_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        listen_socket.bind(("127.0.0.1", port))
        listen_socket.listen(1)
        listen_socket.set_inheritable(True)
        os.environ[boot.LISTEN_FD_ENV] = str(listen_socket.fileno())
        # The server takes ownership of the descriptor from here on.
        listen_socket.detach()

        with tempfile.TemporaryDirectory() as temp_dir:
            store = storage.Storage(database_path=Path(temp_dir) / "test.sqlite3")
            # A different port in the config proves the handed-over socket is
            # adopted rather than a fresh one being bound.
            server = proxy_service.ProxyRuntimeServer(
                {"proxy_port": _free_port()}, store
            )
            try:
                self.assertEqual(server.socket.getsockname()[1], port)
                self.assertEqual(server.server_address[1], port)
                self.assertNotIn(boot.LISTEN_FD_ENV, os.environ)
            finally:
                server.server_close()
                store.close()


if __name__ == "__main__":
    unittest.main()
