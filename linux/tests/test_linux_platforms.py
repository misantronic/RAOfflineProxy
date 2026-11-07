import unittest
from unittest.mock import patch

from linux.raofflineproxy import platform, platforms
from linux.raofflineproxy.platforms import (
    allium,
    darkos,
    generic,
    knulli,
    muos,
    onion,
    rocknix,
    spruce,
    windows,
)


class CurrentPlatformTests(unittest.TestCase):
    def _detect_only(self, matching) -> None:
        for platform in platforms.PLATFORMS:
            patcher = patch.object(platform, "detect", return_value=platform is matching)
            patcher.start()
            self.addCleanup(patcher.stop)

    def test_falls_back_to_generic_when_nothing_matches(self) -> None:
        self._detect_only(None)
        self.assertIs(platforms.current(), generic.PLATFORM)

    def test_returns_the_matching_platform(self) -> None:
        for platform in platforms.PLATFORMS:
            with self.subTest(platform=platform.id):
                with patch.object(platform, "detect", return_value=True):
                    for other in platforms.PLATFORMS:
                        if other is not platform:
                            patcher = patch.object(other, "detect", return_value=False)
                            patcher.start()
                            self.addCleanup(patcher.stop)
                    self.assertIs(platforms.current(), platform)

    def test_first_match_wins_in_detection_order(self) -> None:
        with patch.object(allium.PLATFORM, "detect", return_value=True):
            with patch.object(onion.PLATFORM, "detect", return_value=True):
                self.assertIs(platforms.current(), allium.PLATFORM)

    def test_ids_are_unique_and_resolvable(self) -> None:
        ids = [platform.id for platform in platforms.PLATFORMS]
        self.assertEqual(len(ids), len(set(ids)))
        for platform in platforms.PLATFORMS:
            self.assertIs(platforms.by_id(platform.id), platform)
        self.assertIsNone(platforms.by_id("nope"))

    def test_all_platforms_are_registered(self) -> None:
        modules = (allium, darkos, knulli, muos, onion, rocknix, spruce, windows)
        self.assertEqual({module.PLATFORM for module in modules}, set(platforms.PLATFORMS))


class StartupScriptTests(unittest.TestCase):
    def test_generic_has_no_startup_script(self) -> None:
        self.assertIsNone(generic.PLATFORM.startup_script())

    def test_resolve_follows_the_detected_platform(self) -> None:
        for module in (allium, knulli, muos, onion, rocknix):
            with self.subTest(platform=module.PLATFORM.id):
                with patch.object(platforms, "current", return_value=module.PLATFORM):
                    self.assertEqual(
                        platform.resolve_startup_script_path({}), module.STARTUP_SCRIPT
                    )
        with patch.object(platforms, "current", return_value=darkos.PLATFORM):
            self.assertEqual(platform.resolve_startup_script_path({}), darkos.SERVICE_UNIT)

    def test_resolve_is_none_without_a_platform(self) -> None:
        with patch.object(platforms, "current", return_value=generic.PLATFORM):
            self.assertIsNone(platform.resolve_startup_script_path({}))

    def test_configured_script_wins(self) -> None:
        self.assertEqual(
            platform.resolve_startup_script_path({"startup_script": "/x/boot.sh"}).as_posix(),
            "/x/boot.sh",
        )


if __name__ == "__main__":
    unittest.main()
