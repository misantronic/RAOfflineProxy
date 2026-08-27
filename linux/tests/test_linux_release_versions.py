import re
import unittest
from pathlib import Path

LINUX_DIR = Path(__file__).resolve().parent.parent
CONFIG_PY = LINUX_DIR / "raofflineproxy" / "config.py"

# The release process is manual: no CI builds these bundles and no script bumps the
# version, so each target is another place to forget on release day. A stale APP_VERSION in
# a target's common.sh is not cosmetic — it is the value the app reports as its current
# version, which update_status() compares against the latest release to decide whether an
# update is available. Get it wrong and that target either never offers an update or keeps
# offering one the user already has.
#
# The three declaration styles in use, all matched below: an APP_VERSION/VERSION assignment
# defaulting off RAOFFLINEPROXY_APP_VERSION (allium, spruce, muos, rocknix), and the version
# baked straight into the artifact filename (onion, which does it twice; knulli).
VERSION = r"\d+\.\d+\.\d+(?:-[a-z]+\d+)?"
DECLARATION_PATTERNS = (
    re.compile(rf"RAOFFLINEPROXY_APP_VERSION:-({VERSION})"),
    re.compile(rf"RAOfflineProxy-[A-Za-z]+-v({VERSION})"),
)


def _config_fallback_version() -> str:
    match = re.search(
        rf'APP_VERSION = os\.environ\.get\("RAOFFLINEPROXY_APP_VERSION"\) or "({VERSION})"',
        CONFIG_PY.read_text(encoding="utf-8"),
    )
    assert match, "config.py APP_VERSION fallback not found — did its shape change?"
    return match.group(1)


def _declared_versions(path: Path) -> set[str]:
    text = path.read_text(encoding="utf-8")
    return {m for pattern in DECLARATION_PATTERNS for m in pattern.findall(text)}


def _build_bundles() -> dict[Path, set[str]]:
    return {p: _declared_versions(p) for p in sorted(LINUX_DIR.glob("*/build_bundle.sh"))}


def _common_shs() -> dict[Path, set[str]]:
    versions = {}
    for common in sorted(LINUX_DIR.glob("*/app/*/common.sh")):
        found = re.findall(
            rf"^APP_VERSION=v?({VERSION})", common.read_text(encoding="utf-8"), re.MULTILINE
        )
        versions[common] = set(found)
    return versions


class ReleaseVersionConsistencyTests(unittest.TestCase):
    def test_every_declared_version_matches_the_config_fallback(self) -> None:
        expected = _config_fallback_version()
        drift = {
            str(path.relative_to(LINUX_DIR)): sorted(found - {expected})
            for path, found in {**_build_bundles(), **_common_shs()}.items()
            if found - {expected}
        }
        self.assertEqual(
            drift, {}, f"version drift against config.py's {expected!r} — bump these too"
        )

    def test_every_target_declares_a_version_somewhere(self) -> None:
        # Guards the patterns themselves. Without this, a target that starts declaring its
        # version some new way would contribute zero matches, and the drift check above
        # would pass vacuously on it — silently losing the coverage this file exists for.
        undeclared = [
            str(path.relative_to(LINUX_DIR))
            for path, found in {**_build_bundles(), **_common_shs()}.items()
            if not found
        ]
        self.assertEqual(
            undeclared,
            [],
            "no version found — add its declaration style to DECLARATION_PATTERNS",
        )

    def test_all_known_targets_are_discovered(self) -> None:
        found = {path.parent.name for path in _build_bundles()}
        self.assertEqual(
            found, {"allium", "darkos", "knulli", "muos", "onion", "rocknix", "spruce"}
        )


if __name__ == "__main__":
    unittest.main()
