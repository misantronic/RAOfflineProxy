import json
import socket
import tempfile
import unittest
import zipfile
from pathlib import Path

from linux.raofflineproxy import update


class LinuxUpdateTests(unittest.TestCase):
    def tearDown(self) -> None:
        update.save_cached_update_status = self._original_save_cached_update_status
        update.load_cached_update_status = self._original_load_cached_update_status
        update.fetch_releases = self._original_fetch_releases
        update.read_update_asset = self._original_read_update_asset
        update.time.sleep = self._original_sleep
        update.urllib.request.urlopen = self._original_urlopen
        update.configured_ssl_context = self._original_configured_ssl_context

    def setUp(self) -> None:
        self._original_save_cached_update_status = update.save_cached_update_status
        self._original_load_cached_update_status = update.load_cached_update_status
        self._original_fetch_releases = update.fetch_releases
        self._original_read_update_asset = update.read_update_asset
        self._original_sleep = update.time.sleep
        self._original_urlopen = update.urllib.request.urlopen
        self._original_configured_ssl_context = update.configured_ssl_context

    def test_parse_version_accepts_alpha(self) -> None:
        parsed = update.parse_version("1.3.0-alpha1")

        self.assertIsNotNone(parsed)
        self.assertEqual(parsed.major, 1)
        self.assertEqual(parsed.minor, 3)
        self.assertEqual(parsed.patch, 0)
        self.assertEqual(parsed.stage_rank, 0)
        self.assertEqual(parsed.stage_number, 1)

    def test_parse_version_accepts_beta(self) -> None:
        parsed = update.parse_version("1.2.0-beta1")

        self.assertIsNotNone(parsed)
        self.assertEqual(parsed.stage_rank, 1)
        self.assertEqual(parsed.stage_number, 1)

    def test_find_platform_asset_url_matches_knulli_shell_asset(self) -> None:
        asset_url = update.find_platform_asset_url(
            "knulli",
            [
                {"name": "RAOfflineProxy-Onion-v1.3.0-alpha1.zip", "browser_download_url": "https://example.com/onion.zip"},
                {"name": "RAOfflineProxy-Knulli-v1.3.0-alpha1-Install.sh", "browser_download_url": "https://example.com/knulli.sh"},
            ],
        )

        self.assertEqual(asset_url, "https://example.com/knulli.sh")

    def test_find_platform_asset_url_matches_onion_zip_asset(self) -> None:
        asset_url = update.find_platform_asset_url(
            "onion",
            [
                {"name": "RAOfflineProxy-Knulli-v1.3.0-alpha1-Install.sh", "browser_download_url": "https://example.com/knulli.sh"},
                {"name": "RAOfflineProxy-Onion-v1.3.0-alpha1.zip", "browser_download_url": "https://example.com/onion.zip"},
            ],
        )

        self.assertEqual(asset_url, "https://example.com/onion.zip")

    def test_find_platform_asset_url_rejects_wrong_asset_type(self) -> None:
        onion_asset_url = update.find_platform_asset_url(
            "onion",
            [
                {"name": "RAOfflineProxy-Onion-v1.3.0-alpha1.sh", "browser_download_url": "https://example.com/onion.sh"}
            ],
        )
        knulli_asset_url = update.find_platform_asset_url(
            "knulli",
            [
                {"name": "RAOfflineProxy-Knulli-v1.3.0-alpha1.zip", "browser_download_url": "https://example.com/knulli.zip"}
            ],
        )

        self.assertIsNone(onion_asset_url)
        self.assertIsNone(knulli_asset_url)

    def test_fetch_latest_release_ignores_older_versions(self) -> None:
        update.fetch_releases = lambda _platform: [
            update.ReleaseCandidate(
                version_name="1.0.0-alpha1",
                parsed_version=update.parse_version("1.0.0-alpha1"),
                release_url="https://example.com/release-old",
                asset_url="https://example.com/asset-old",
            ),
            update.ReleaseCandidate(
                version_name="1.3.0-alpha1",
                parsed_version=update.parse_version("1.3.0-alpha1"),
                release_url="https://example.com/release-new",
                asset_url="https://example.com/asset-new",
            ),
        ]

        succeeded, latest = update.fetch_latest_release("knulli", "1.1.0-alpha1")

        self.assertTrue(succeeded)
        self.assertIsNotNone(latest)
        self.assertEqual(latest.version_name, "1.3.0-alpha1")

    def test_fetch_latest_release_prefers_stable_over_beta_and_alpha(self) -> None:
        update.fetch_releases = lambda _platform: [
            update.ReleaseCandidate(
                version_name="1.1.0-alpha1",
                parsed_version=update.parse_version("1.1.0-alpha1"),
                release_url="https://example.com/release-alpha",
                asset_url="https://example.com/asset-alpha",
            ),
            update.ReleaseCandidate(
                version_name="1.1.0-beta1",
                parsed_version=update.parse_version("1.1.0-beta1"),
                release_url="https://example.com/release-beta",
                asset_url="https://example.com/asset-beta",
            ),
            update.ReleaseCandidate(
                version_name="1.1.0",
                parsed_version=update.parse_version("1.1.0"),
                release_url="https://example.com/release-stable",
                asset_url="https://example.com/asset-stable",
            ),
        ]

        succeeded, latest = update.fetch_latest_release("onion", "1.0.0-alpha1")

        self.assertTrue(succeeded)
        self.assertIsNotNone(latest)
        self.assertEqual(latest.version_name, "1.1.0")

    def test_fetch_latest_release_returns_none_when_current_is_newer(self) -> None:
        update.fetch_releases = lambda _platform: [
            update.ReleaseCandidate(
                version_name="1.1.0-beta1",
                parsed_version=update.parse_version("1.1.0-beta1"),
                release_url="https://example.com/release-beta",
                asset_url="https://example.com/asset-beta",
            )
        ]

        succeeded, latest = update.fetch_latest_release("knulli", "1.1.0")

        self.assertTrue(succeeded)
        self.assertIsNone(latest)

    def test_fetch_latest_release_returns_none_for_invalid_current_version(self) -> None:
        succeeded, latest = update.fetch_latest_release("onion", "not-a-version")

        self.assertTrue(succeeded)
        self.assertIsNone(latest)

    def test_update_status_uses_cached_result_when_recent(self) -> None:
        cached = {
            "current_version": "1.1.0-alpha1",
            "update_available": True,
            "latest_version": "1.3.0-alpha1",
            "release_url": "https://example.com/release",
            "asset_url": "https://example.com/asset",
            "checked_at": 2_000_000_000,
        }
        update.load_cached_update_status = lambda _platform: cached
        update.fetch_releases = lambda _platform: (_ for _ in ()).throw(AssertionError("should not fetch releases"))

        original_time = update.time.time
        original_current_version = update.current_version
        try:
            update.time.time = lambda: 2_000_000_100
            update.current_version = lambda: "1.1.0-alpha1"
            result = update.update_status("onion")
        finally:
            update.time.time = original_time
            update.current_version = original_current_version

        self.assertTrue(result.update_available)
        self.assertEqual(result.latest_version, "1.3.0-alpha1")

    def test_update_status_recent_cache_ignores_stale_cached_current_version_after_upgrade(self) -> None:
        cached = {
            "current_version": "1.2.0-alpha4",
            "update_available": True,
            "latest_version": "1.2.1-alpha4",
            "release_url": "https://example.com/release",
            "asset_url": "https://example.com/asset",
            "checked_at": 2_000_000_000,
        }
        update.load_cached_update_status = lambda _platform: cached
        update.fetch_releases = lambda _platform: (_ for _ in ()).throw(AssertionError("should not fetch releases"))

        original_time = update.time.time
        original_current_version = update.current_version
        try:
            update.time.time = lambda: 2_000_000_100
            update.current_version = lambda: "1.2.1-alpha4"
            result = update.update_status("onion")
        finally:
            update.time.time = original_time
            update.current_version = original_current_version

        self.assertFalse(result.update_available)
        self.assertIsNone(result.latest_version)

    def test_dict_to_update_info_recomputes_cached_update_availability(self) -> None:
        original_current_version = update.current_version
        try:
            update.current_version = lambda: "1.2.1-alpha4"
            result = update.dict_to_update_info(
                {
                    "current_version": "1.2.0-alpha4",
                    "update_available": True,
                    "latest_version": "1.2.1-alpha4",
                    "release_url": "https://example.com/release",
                    "asset_url": "https://example.com/asset",
                    "checked_at": 123,
                }
            )
        finally:
            update.current_version = original_current_version

        self.assertEqual(result.current_version, "1.2.1-alpha4")
        self.assertFalse(result.update_available)
        self.assertIsNone(result.latest_version)

    def test_update_status_saves_platform_specific_result(self) -> None:
        saved = {}
        update.load_cached_update_status = lambda _platform: None
        update.save_cached_update_status = lambda platform, result: saved.setdefault(platform, result.to_dict())
        update.fetch_releases = lambda _platform: [
            update.ReleaseCandidate(
                version_name="1.3.0-alpha1",
                parsed_version=update.parse_version("1.3.0-alpha1"),
                release_url="https://example.com/release-new",
                asset_url="https://example.com/asset-new",
            )
        ]

        original_current_version = update.current_version

        try:
            update.current_version = lambda: "1.2.2-alpha1"
            result = update.update_status("knulli", force=True)
        finally:
            update.current_version = original_current_version

        self.assertTrue(result.update_available)
        self.assertIn("knulli", saved)
        self.assertEqual(saved["knulli"]["latest_version"], "1.3.0-alpha1")

    def test_update_status_preserves_cached_result_when_fetch_fails(self) -> None:
        cached = {
            "current_version": "1.0.0-alpha1",
            "update_available": True,
            "latest_version": "1.1.0-alpha1",
            "release_url": "https://example.com/release",
            "asset_url": "https://example.com/asset",
            "checked_at": 123,
        }
        update.load_cached_update_status = lambda _platform: cached
        update.fetch_releases = lambda _platform: None
        original_current_version = update.current_version

        try:
            update.current_version = lambda: "1.0.0-alpha1"
            result = update.update_status("onion", force=True)
        finally:
            update.current_version = original_current_version

        self.assertTrue(result.update_available)
        self.assertEqual(result.latest_version, "1.1.0-alpha1")

    def test_update_status_returns_uncached_failure_without_writing_false_negative(self) -> None:
        saved = []
        update.load_cached_update_status = lambda _platform: None
        update.save_cached_update_status = lambda platform, result: saved.append((platform, result))
        update.fetch_releases = lambda _platform: None

        result = update.update_status("onion", force=True)

        self.assertFalse(result.update_available)
        self.assertEqual(result.checked_at, 0)
        self.assertEqual(saved, [])

    def test_download_knulli_update_installer_retries_after_connection_error(self) -> None:
        attempts = []
        sleeps = []

        def fake_read_update_asset(_asset_url: str) -> bytes:
            attempts.append(True)
            if len(attempts) == 1:
                raise ConnectionResetError("Remote end closed connection")
            return b"#!/bin/sh\nexit 0\n"

        update.read_update_asset = fake_read_update_asset
        update.time.sleep = lambda seconds: sleeps.append(seconds)

        with tempfile.TemporaryDirectory() as temp_dir:
            destination = Path(temp_dir) / "installer.sh"
            written = update.download_knulli_update_installer(
                "https://example.com/installer.sh",
                destination=destination,
            )

            self.assertEqual(written, destination)
            self.assertTrue(destination.exists())
            self.assertEqual(len(attempts), 2)
            self.assertEqual(sleeps, [update.INSTALLER_DOWNLOAD_RETRY_DELAY_SECONDS])

    def test_atomic_write_executable_replaces_destination(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            destination = Path(temp_dir) / "installer.sh"
            destination.write_text("old", encoding="utf-8")

            update.atomic_write_executable(destination, b"#!/bin/sh\nexit 0\n")

            self.assertEqual(destination.read_bytes(), b"#!/bin/sh\nexit 0\n")

    def test_install_onion_update_archive_replaces_app_dir(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            app_dir = root / "RAOfflineProxy"
            app_dir.mkdir(parents=True)
            (app_dir / "old.txt").write_text("old", encoding="utf-8")
            data_dir = app_dir / "data"
            data_dir.mkdir()
            (data_dir / "proxy.sqlite3").write_text("cached-db", encoding="utf-8")

            archive_path = root / "update.zip"
            with zipfile.ZipFile(archive_path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
                archive.writestr("App/RAOfflineProxy/new.txt", "new")
                archive.writestr("App/RAOfflineProxy/common.sh", "APP_VERSION=v1.2.0-alpha2\n")
                archive.writestr("App/RAOfflineProxy/data/proxy.sqlite3", "fresh-db")
                launch_info = zipfile.ZipInfo("App/RAOfflineProxy/launch.sh")
                launch_info.external_attr = 0o755 << 16
                archive.writestr(launch_info, "#!/bin/sh\nexit 0\n")

            original_current_version = update.current_version
            try:
                update.current_version = lambda: "1.1.0-alpha1"
                update.install_onion_update_archive(archive_path, app_dir)
            finally:
                update.current_version = original_current_version

            self.assertFalse((app_dir / "old.txt").exists())
            self.assertEqual((app_dir / "new.txt").read_text(encoding="utf-8"), "new")
            self.assertEqual((app_dir / "data" / "proxy.sqlite3").read_text(encoding="utf-8"), "cached-db")
            self.assertEqual((app_dir / "launch.sh").stat().st_mode & 0o777, 0o755)

    def test_install_onion_update_archive_clears_stale_update_status(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            app_dir = root / "RAOfflineProxy"
            data_dir = app_dir / "data"
            data_dir.mkdir(parents=True)
            (data_dir / "update_status.json").write_text("stale", encoding="utf-8")

            archive_path = root / "update.zip"
            with zipfile.ZipFile(archive_path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
                archive.writestr("App/RAOfflineProxy/common.sh", "APP_VERSION=v1.3.0-alpha1\n")
                archive.writestr("App/RAOfflineProxy/data/proxy.sqlite3", "fresh-db")

            original_current_version = update.current_version
            try:
                update.current_version = lambda: "1.2.0-alpha4"
                update.install_onion_update_archive(archive_path, app_dir)
            finally:
                update.current_version = original_current_version

            self.assertFalse((app_dir / "data" / "update_status.json").exists())

    def test_knulli_install_script_clears_config_update_status(self) -> None:
        install_script = Path(__file__).resolve().parents[1] / "knulli" / "scripts" / "install.sh"

        contents = install_script.read_text(encoding="utf-8")

        self.assertIn(
            'UPDATE_STATUS_FILE="/userdata/system/.config/raofflineproxy/update_status.json"',
            contents,
        )

    def test_install_onion_update_archive_resets_data_for_major_upgrade(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            app_dir = root / "RAOfflineProxy"
            app_dir.mkdir(parents=True)
            data_dir = app_dir / "data"
            data_dir.mkdir()
            (data_dir / "proxy.sqlite3").write_text("cached-db", encoding="utf-8")

            archive_path = root / "update.zip"
            with zipfile.ZipFile(archive_path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
                archive.writestr("App/RAOfflineProxy/common.sh", "APP_VERSION=v2.0.0-alpha1\n")
                archive.writestr("App/RAOfflineProxy/data/proxy.sqlite3", "fresh-db")

            original_current_version = update.current_version
            try:
                update.current_version = lambda: "1.3.0-alpha1"
                update.install_onion_update_archive(archive_path, app_dir)
            finally:
                update.current_version = original_current_version

            self.assertEqual((app_dir / "data" / "proxy.sqlite3").read_text(encoding="utf-8"), "fresh-db")

    def test_download_onion_update_archive_writes_zip_non_executable(self) -> None:
        update.read_update_asset = lambda _asset_url: b"zip-bytes"

        with tempfile.TemporaryDirectory() as temp_dir:
            destination = Path(temp_dir) / "update.zip"
            written = update.download_onion_update_archive(
                "https://example.com/onion.zip",
                destination=destination,
            )

            self.assertEqual(written, destination)
            self.assertEqual(destination.read_bytes(), b"zip-bytes")

    def test_fetch_releases_retries_after_timeout(self) -> None:
        attempts = []
        sleeps = []

        class FakeResponse:
            def __enter__(self):
                return self

            def __exit__(self, exc_type, exc, tb):
                return False

            def read(self):
                return json.dumps(
                    [
                        {
                            "draft": False,
                            "tag_name": "v1.3.0-alpha1",
                            "html_url": "https://example.com/release",
                            "assets": [
                                {
                                    "name": "RAOfflineProxy-Onion-v1.3.0-alpha1.zip",
                                    "browser_download_url": "https://example.com/onion.zip",
                                }
                            ],
                        }
                    ]
                ).encode("utf-8")

        def fake_urlopen(_request, timeout=0, context=None):
            attempts.append(timeout)
            if len(attempts) == 1:
                raise socket.timeout("The read operation timed out")
            return FakeResponse()

        update.urllib.request.urlopen = fake_urlopen
        update.time.sleep = lambda seconds: sleeps.append(seconds)
        update.configured_ssl_context = lambda: object()

        releases = update.fetch_releases("onion")

        self.assertIsNotNone(releases)
        self.assertEqual(len(releases), 1)
        self.assertEqual(len(attempts), 2)
        self.assertEqual(sleeps, [update.RELEASES_FETCH_RETRY_DELAY_SECONDS])


if __name__ == "__main__":
    unittest.main()
