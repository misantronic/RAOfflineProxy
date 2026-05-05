import tempfile
import unittest
from pathlib import Path

from linux.raofflineproxy import auth
from linux.raofflineproxy import cache_keys
from linux.raofflineproxy import retroarch_cfg
from linux.raofflineproxy import storage


class LinuxAuthTests(unittest.TestCase):
    def test_password_credentials_are_not_returned_as_token_credentials(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            cfg_path = Path(temp_dir) / "retroarch.cfg"
            cfg_path.write_text(
                'cheevos_username = "misantronic"\ncheevos_password = "secret"\n',
                encoding="utf-8",
            )

            self.assertIsNone(
                retroarch_cfg.load_retroarch_token_credentials(str(cfg_path))
            )
            self.assertEqual(
                retroarch_cfg.load_retroarch_password_credentials(str(cfg_path)),
                {"user": "misantronic", "password": "secret"},
            )

    def test_resolve_credentials_logs_in_and_caches_token(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            db_path = root / "test.sqlite3"
            cfg_path = root / "retroarch.cfg"
            cfg_path.write_text(
                'cheevos_username = "misantronic"\ncheevos_password = "secret"\n',
                encoding="utf-8",
            )
            store = storage.Storage(database_path=db_path)
            original_http_get = auth.http_get
            try:
                captured = {}

                def fake_http_get(url: str, _user_agent: str) -> str:
                    captured["url"] = url
                    return '{"Success":true,"User":"misantronic","Token":"token"}'

                auth.http_get = fake_http_get

                credentials = auth.resolve_credentials(
                    store,
                    {"retroarch_cfg": str(cfg_path)},
                    "RetroArch/1.20.0",
                )

                self.assertEqual(credentials, {"user": "misantronic", "token": "token"})
                self.assertIn("r=login2", captured["url"])
                self.assertIsNotNone(store.get_cache(cache_keys.login("misantronic")))
            finally:
                auth.http_get = original_http_get
                store.close()

    def test_resolve_credentials_uses_token_before_password(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            db_path = root / "test.sqlite3"
            cfg_path = root / "retroarch.cfg"
            cfg_path.write_text(
                'cheevos_username = "misantronic"\n'
                'cheevos_token = "cfg-token"\n'
                'cheevos_password = "secret"\n',
                encoding="utf-8",
            )
            store = storage.Storage(database_path=db_path)
            original_http_get = auth.http_get
            try:

                def fake_http_get(_url: str, _user_agent: str) -> str:
                    raise AssertionError(
                        "login2 should not be called when token exists"
                    )

                auth.http_get = fake_http_get

                credentials = auth.resolve_credentials(
                    store,
                    {"retroarch_cfg": str(cfg_path)},
                    "RetroArch/1.20.0",
                )

                self.assertEqual(
                    credentials,
                    {"user": "misantronic", "token": "cfg-token"},
                )
                self.assertIsNotNone(store.get_cache(cache_keys.login("misantronic")))
            finally:
                auth.http_get = original_http_get
                store.close()

    def test_resolve_credentials_uses_cfg_token_before_cached_token(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            db_path = root / "test.sqlite3"
            cfg_path = root / "retroarch.cfg"
            cfg_path.write_text(
                'cheevos_username = "misantronic"\ncheevos_token = "cfg-token"\n',
                encoding="utf-8",
            )
            store = storage.Storage(database_path=db_path)
            try:
                store.upsert_cache(
                    cache_keys.login("misantronic"),
                    '{"Success":true,"User":"misantronic","Token":"old-token"}',
                )

                credentials = auth.resolve_credentials(
                    store,
                    {"retroarch_cfg": str(cfg_path)},
                    "RetroArch/1.20.0",
                )

                self.assertEqual(
                    credentials,
                    {"user": "misantronic", "token": "cfg-token"},
                )
            finally:
                store.close()


if __name__ == "__main__":
    unittest.main()
