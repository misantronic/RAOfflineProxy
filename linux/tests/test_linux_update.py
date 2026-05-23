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

    def test_parse_version_accepts_linux_alpha(self) -> None:
        parsed = update.parse_version("1.1.0-linux-alpha")

        self.assertIsNotNone(parsed)
        self.assertEqual(parsed.major, 1)
        self.assertEqual(parsed.minor, 1)
        self.assertEqual(parsed.patch, 0)
        self.assertEqual(parsed.stage_rank, 0)

    def test_parse_version_accepts_linux_beta(self) -> None:
        parsed = update.parse_version("1.2.0-linux-beta")

        self.assertIsNotNone(parsed)
        self.assertEqual(parsed.stage_rank, 1)

    def test_find_platform_asset_url_matches_knulli_shell_asset(self) -> None:
        asset_url = update.find_platform_asset_url(
            "knulli",
            [
                {"name": "RAOfflineProxy-Onion-v1.1.0-linux-alpha.zip", "browser_download_url": "https://example.com/onion.zip"},
                {"name": "RAOfflineProxy-Knulli-v1.1.0-linux-alpha-Install.sh", "browser_download_url": "https://example.com/knulli.sh"},
            ],
        )

        self.assertEqual(asset_url, "https://example.com/knulli.sh")

    def test_find_platform_asset_url_matches_onion_zip_asset(self) -> None:
        asset_url = update.find_platform_asset_url(
            "onion",
            [
                {"name": "RAOfflineProxy-Knulli-v1.1.0-linux-alpha-Install.sh", "browser_download_url": "https://example.com/knulli.sh"},
                {"name": "RAOfflineProxy-Onion-v1.1.0-linux-alpha.zip", "browser_download_url": "https://example.com/onion.zip"},
            ],
        )

        self.assertEqual(asset_url, "https://example.com/onion.zip")

    def test_fetch_latest_release_ignores_older_versions(self) -> None:
        update.fetch_releases = lambda _platform: [
            update.ReleaseCandidate(
                version_name="1.0.0-linux-alpha",
                parsed_version=update.parse_version("1.0.0-linux-alpha"),
                release_url="https://example.com/release-old",
                asset_url="https://example.com/asset-old",
            ),
            update.ReleaseCandidate(
                version_name="1.2.0-linux-alpha",
                parsed_version=update.parse_version("1.2.0-linux-alpha"),
                release_url="https://example.com/release-new",
                asset_url="https://example.com/asset-new",
            ),
        ]

        succeeded, latest = update.fetch_latest_release("knulli", "1.1.0-linux-alpha")

        self.assertTrue(succeeded)
        self.assertIsNotNone(latest)
        self.assertEqual(latest.version_name, "1.2.0-linux-alpha")

    def test_update_status_uses_cached_result_when_recent(self) -> None:
        cached = {
            "current_version": "1.1.0-linux-alpha",
            "update_available": True,
            "latest_version": "1.2.0-linux-alpha",
            "release_url": "https://example.com/release",
            "asset_url": "https://example.com/asset",
            "checked_at": 2_000_000_000,
        }
        update.load_cached_update_status = lambda _platform: cached
        update.fetch_releases = lambda _platform: (_ for _ in ()).throw(AssertionError("should not fetch releases"))

        original_time = update.time.time
        try:
            update.time.time = lambda: 2_000_000_100
            result = update.update_status("onion")
        finally:
            update.time.time = original_time

        self.assertTrue(result.update_available)
        self.assertEqual(result.latest_version, "1.2.0-linux-alpha")

    def test_update_status_saves_platform_specific_result(self) -> None:
        saved = {}
        update.load_cached_update_status = lambda _platform: None
        update.save_cached_update_status = lambda platform, result: saved.setdefault(platform, result.to_dict())
        update.fetch_releases = lambda _platform: [
            update.ReleaseCandidate(
                version_name="1.2.0-linux-alpha",
                parsed_version=update.parse_version("1.2.0-linux-alpha"),
                release_url="https://example.com/release-new",
                asset_url="https://example.com/asset-new",
            )
        ]

        result = update.update_status("knulli", force=True)

        self.assertTrue(result.update_available)
        self.assertIn("knulli", saved)
        self.assertEqual(saved["knulli"]["latest_version"], "1.2.0-linux-alpha")

    def test_update_status_preserves_cached_result_when_fetch_fails(self) -> None:
        cached = {
            "current_version": "1.0.0-linux-alpha",
            "update_available": True,
            "latest_version": "1.1.0-linux-alpha",
            "release_url": "https://example.com/release",
            "asset_url": "https://example.com/asset",
            "checked_at": 123,
        }
        update.load_cached_update_status = lambda _platform: cached
        update.fetch_releases = lambda _platform: None

        result = update.update_status("onion", force=True)

        self.assertTrue(result.update_available)
        self.assertEqual(result.latest_version, "1.1.0-linux-alpha")

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

            archive_path = root / "update.zip"
            with zipfile.ZipFile(archive_path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
                archive.writestr("App/RAOfflineProxy/new.txt", "new")
                launch_info = zipfile.ZipInfo("App/RAOfflineProxy/launch.sh")
                launch_info.external_attr = 0o755 << 16
                archive.writestr(launch_info, "#!/bin/sh\nexit 0\n")

            update.install_onion_update_archive(archive_path, app_dir)

            self.assertFalse((app_dir / "old.txt").exists())
            self.assertEqual((app_dir / "new.txt").read_text(encoding="utf-8"), "new")
            self.assertEqual((app_dir / "launch.sh").stat().st_mode & 0o777, 0o755)

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
                            "tag_name": "v1.1.0-linux-alpha",
                            "html_url": "https://example.com/release",
                            "assets": [
                                {
                                    "name": "RAOfflineProxy-Onion-v1.1.0-linux-alpha.zip",
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
