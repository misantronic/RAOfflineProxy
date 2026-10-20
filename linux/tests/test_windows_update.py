import pytest

from linux.raofflineproxy.windows.update import (
    GITHUB_RELEASES_URL,
    WINDOWS_NIGHTLY_RELEASE_URL,
    allowed_download,
    find_checksum_asset,
)

FORK_RELEASES = "https://api.github.com/repos/JoeysRetroHandhelds/RAOfflineProxy/releases"


@pytest.mark.parametrize(
    "url, releases_url, allowed",
    [
        ("https://github.com/misantronic/RAOfflineProxy/releases/download/v2.3/RAOfflineProxy-Windows-v2.3.zip", GITHUB_RELEASES_URL, True),
        ("https://github.com/misantronic/RAOfflineProxy-nightly/releases/download/nightly-windows/x.zip", GITHUB_RELEASES_URL, True),
        ("https://github.com/someone/RAOfflineProxy/releases/download/v1/x.zip", GITHUB_RELEASES_URL, False),
        ("https://github.com/misantronic/RAOfflineProxy/archive/x.zip", GITHUB_RELEASES_URL, False),
        ("http://github.com/misantronic/RAOfflineProxy/releases/download/v1/x.zip", GITHUB_RELEASES_URL, False),
        ("https://example.com/x.zip", GITHUB_RELEASES_URL, False),
        # A test releases list may use its own repository, or its own host.
        ("https://github.com/JoeysRetroHandhelds/RAOfflineProxy/releases/download/v1/x.zip", FORK_RELEASES, True),
        ("http://127.0.0.1:5000/x.zip", "http://127.0.0.1:5000/releases.json", True),
        ("http://127.0.0.1:6000/x.zip", "http://127.0.0.1:5000/releases.json", False),
    ],
)
def test_only_release_downloads_are_allowed(url, releases_url, allowed):
    assert allowed_download(url, releases_url) is allowed


def test_checksum_asset_matches_the_zip_name():
    assets = [
        {"name": "RAOfflineProxy-Windows-v2.3.zip", "browser_download_url": "https://x/a.zip"},
        {"name": "RAOfflineProxy-Windows-v2.3.zip.sha256", "browser_download_url": "https://x/a.zip.sha256"},
        {"name": "RAOfflineProxy-Knulli-v2.3-Install.sh.sha256", "browser_download_url": "https://x/k.sha256"},
    ]
    zip_url = "https://github.com/o/r/releases/download/v2.3/RAOfflineProxy-Windows-v2.3.zip"
    assert find_checksum_asset(assets, zip_url) == "https://x/a.zip.sha256"
    assert find_checksum_asset(assets[:1], zip_url) is None


def test_checksum_is_required_only_for_official_releases():
    from linux.raofflineproxy.windows.update import checksum_required

    assert checksum_required(GITHUB_RELEASES_URL)
    assert not checksum_required(FORK_RELEASES)
    assert not checksum_required("http://127.0.0.1:5000/releases.json")


def test_an_official_release_without_a_checksum_is_refused(tmp_path):
    from unittest import mock

    from linux.raofflineproxy.windows import update

    zip_url = "https://github.com/misantronic/RAOfflineProxy/releases/download/v2.3/RAOfflineProxy-Windows-v2.3.zip"
    with mock.patch("linux.raofflineproxy.windows.paths.portable_root", return_value=tmp_path), \
            mock.patch.object(update, "releases_url", return_value=GITHUB_RELEASES_URL), \
            mock.patch.object(update, "_download") as download:
        with pytest.raises(RuntimeError, match="no checksum file"):
            update.download_and_launch_installer(zip_url, checksum_url=None)
    download.assert_not_called()


def test_nightly_installs_follow_the_windows_release():
    assert WINDOWS_NIGHTLY_RELEASE_URL.endswith("/RAOfflineProxy-nightly/releases/tags/nightly-windows")
