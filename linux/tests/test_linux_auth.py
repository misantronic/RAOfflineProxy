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

    def test_resolve_credentials_uses_cached_token_when_not_invalid(self) -> None:
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
                store.upsert_cache(
                    cache_keys.login("misantronic"),
                    '{"Success":true,"User":"misantronic","Token":"cached-token"}',
                )

                def fake_http_get(_url: str, _user_agent: str) -> str:
                    raise AssertionError(
                        "login2 should not be called when cached token is valid"
                    )

                auth.http_get = fake_http_get

                credentials = auth.resolve_credentials(
                    store,
                    {"retroarch_cfg": str(cfg_path)},
                    "RetroArch/1.20.0",
                )

                self.assertEqual(
                    credentials,
                    {"user": "misantronic", "token": "cached-token"},
                )
            finally:
                auth.http_get = original_http_get
                store.close()

    def test_resolve_credentials_falls_back_to_password_login_when_cached_token_invalid(
        self,
    ) -> None:
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
                store.upsert_cache(
                    cache_keys.login("misantronic"),
                    '{"Success":true,"User":"misantronic","Token":"stale-token"}',
                )
                store.mark_token_invalid("stale-token")

                def fake_http_get(url: str, _user_agent: str) -> str:
                    self.assertIn("r=login2", url)
                    return '{"Success":true,"User":"misantronic","Token":"fresh-token"}'

                auth.http_get = fake_http_get

                credentials = auth.resolve_credentials(
                    store,
                    {"retroarch_cfg": str(cfg_path)},
                    "RetroArch/1.20.0",
                )

                self.assertEqual(
                    credentials,
                    {"user": "misantronic", "token": "fresh-token"},
                )
                self.assertFalse(store.is_token_invalid("fresh-token"))
            finally:
                auth.http_get = original_http_get
                store.close()

    def test_resolve_credentials_falls_back_to_stale_cache_when_password_login_fails(
        self,
    ) -> None:
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
                store.upsert_cache(
                    cache_keys.login("misantronic"),
                    '{"Success":true,"User":"misantronic","Token":"stale-token"}',
                )
                store.mark_token_invalid("stale-token")

                def fake_http_get(_url: str, _user_agent: str) -> str:
                    raise OSError("offline")

                auth.http_get = fake_http_get

                credentials = auth.resolve_credentials(
                    store,
                    {"retroarch_cfg": str(cfg_path)},
                    "RetroArch/1.20.0",
                )

                self.assertEqual(
                    credentials,
                    {"user": "misantronic", "token": "stale-token"},
                )
            finally:
                auth.http_get = original_http_get
                store.close()


class LinuxRocknixAppendConfigCredentialTests(unittest.TestCase):
    """ROCKNIX strips credentials out of retroarch.cfg on every game launch and
    writes them only into its --appendconfig file, so that file has to be read."""

    def test_resolves_password_credentials_from_rocknix_append_config(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            cfg = root / "retroarch.cfg"
            append_cfg = root / ".retroarch.cfg"
            # What ROCKNIX leaves behind: the cheevos keys seded out of retroarch.cfg.
            cfg.write_text('cheevos_enable = "true"\n', encoding="utf-8")
            append_cfg.write_text(
                'cheevos_username = "misantronic"\ncheevos_password = "hunter2"\n',
                encoding="utf-8",
            )
            store = storage.Storage(database_path=root / "test.sqlite3")
            original_login = auth.login_and_cache_token
            try:
                auth.login_and_cache_token = (
                    lambda _store, _cfg, credentials, _ua: {
                        "user": credentials["user"],
                        "token": "issued",
                    }
                )

                resolved = auth.resolve_credentials(
                    store,
                    {"retroarch_cfg": str(cfg), "rocknix_append_cfg": str(append_cfg)},
                )

                self.assertEqual(resolved, {"user": "misantronic", "token": "issued"})
            finally:
                auth.login_and_cache_token = original_login
                store.close()

    def test_last_occurrence_wins_across_appended_launches(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            cfg = root / "retroarch.cfg"
            append_cfg = root / ".retroarch.cfg"
            cfg.write_text("", encoding="utf-8")
            # ROCKNIX appends without truncating, so stale values sit above current ones.
            append_cfg.write_text(
                'cheevos_username = "olduser"\ncheevos_token = "oldtoken"\n'
                'cheevos_username = "misantronic"\ncheevos_token = "newtoken"\n',
                encoding="utf-8",
            )
            store = storage.Storage(database_path=root / "test.sqlite3")
            try:
                resolved = auth.resolve_credentials(
                    store,
                    {"retroarch_cfg": str(cfg), "rocknix_append_cfg": str(append_cfg)},
                )

                self.assertEqual(resolved, {"user": "misantronic", "token": "newtoken"})
            finally:
                store.close()

    def test_retroarch_cfg_still_takes_precedence(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            cfg = root / "retroarch.cfg"
            append_cfg = root / ".retroarch.cfg"
            cfg.write_text(
                'cheevos_username = "fromcfg"\ncheevos_token = "cfgtoken"\n',
                encoding="utf-8",
            )
            append_cfg.write_text(
                'cheevos_username = "fromappend"\ncheevos_token = "appendtoken"\n',
                encoding="utf-8",
            )
            store = storage.Storage(database_path=root / "test.sqlite3")
            try:
                resolved = auth.resolve_credentials(
                    store,
                    {"retroarch_cfg": str(cfg), "rocknix_append_cfg": str(append_cfg)},
                )

                self.assertEqual(resolved, {"user": "fromcfg", "token": "cfgtoken"})
            finally:
                store.close()


if __name__ == "__main__":
    unittest.main()
