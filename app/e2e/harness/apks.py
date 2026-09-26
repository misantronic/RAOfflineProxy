from __future__ import annotations

import os
import subprocess
from dataclasses import dataclass
from pathlib import Path

GRADLE_TASKS = (
    ":app:assembleE2e",
    ":e2e-stub-emulator:assembleRetroarchDebug",
    ":e2e-stub-emulator:assembleFlycastDebug",
)


@dataclass(frozen=True)
class Apks:
    app: Path
    retroarch_stub: Path
    flycast_stub: Path

    def all(self) -> tuple:
        return (self.app, self.retroarch_stub, self.flycast_stub)


def default_apks(repo_root: Path) -> Apks:
    stub_outputs = repo_root / "e2e-stub-emulator" / "build" / "outputs" / "apk"
    return Apks(
        app=Path(
            os.environ.get("RAOP_ANDROID_E2E_APK")
            or repo_root / "app" / "build" / "outputs" / "apk" / "e2e" / "app-e2e.apk"
        ),
        retroarch_stub=stub_outputs / "retroarch" / "debug" / "e2e-stub-emulator-retroarch-debug.apk",
        flycast_stub=stub_outputs / "flycast" / "debug" / "e2e-stub-emulator-flycast-debug.apk",
    )


def build_apks(repo_root: Path) -> Apks:
    """Builds the e2e APKs unless they already exist or a prebuilt app APK is given."""
    apks = default_apks(repo_root)
    if all(apk.exists() for apk in apks.all()) and os.environ.get("RAOP_ANDROID_E2E_REBUILD") != "1":
        return apks
    subprocess.run(
        [str(repo_root / "gradlew"), *GRADLE_TASKS],
        cwd=repo_root,
        check=True,
        timeout=3600,
    )
    missing = [str(apk) for apk in apks.all() if not apk.exists()]
    if missing:
        raise FileNotFoundError("gradle finished but APKs are missing: %s" % ", ".join(missing))
    return apks
