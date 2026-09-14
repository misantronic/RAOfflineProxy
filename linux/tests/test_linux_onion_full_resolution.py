import unittest
from pathlib import Path

LINUX_DIR = Path(__file__).resolve().parents[1]
ONION_APP_DIR = LINUX_DIR / "onion" / "app" / "RAOfflineProxy"
FULL_RESOLUTION_MARKER = ONION_APP_DIR / "full_resolution"
BUILD_BUNDLE = LINUX_DIR / "onion" / "build_bundle.sh"

# Onion's runtime.sh derives this path from the App launch command
# (get_full_resolution_path: `cmd_to_run.sh | cut -d' ' -f 2 | sed 's/;/\/full_resolution/'`)
# and only tests for its existence. Without it, runtime launches the app in MainUI's
# 640x480 mode while /tmp/screen_resolution already reports the detected 752x560 panel,
# so menu_sdl lays out for a framebuffer it does not get. The file is empty by design and
# nothing else in the tree references it, which is exactly what makes it easy to delete by
# mistake.


class OnionFullResolutionMarkerTests(unittest.TestCase):
    def test_marker_exists_and_is_empty(self):
        self.assertTrue(
            FULL_RESOLUTION_MARKER.is_file(),
            f"{FULL_RESOLUTION_MARKER} is missing — Onion launches the menu at 640x480 without it",
        )
        self.assertEqual(
            FULL_RESOLUTION_MARKER.stat().st_size,
            0,
            "the marker must stay empty: runtime.sh only tests for its existence",
        )

    def test_bundle_copies_the_whole_app_dir(self):
        self.assertIn(
            'cp -R "${SCRIPT_DIR}/app/RAOfflineProxy/." "${APP_DIR}/"',
            BUILD_BUNDLE.read_text(encoding="utf-8"),
            "the marker ships only because the bundle copies the app dir wholesale",
        )
