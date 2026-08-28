from __future__ import annotations

import os
import subprocess
from pathlib import Path


class BundleError(RuntimeError):
    pass


def newest_installer(repo_root: Path, glob_pattern: str) -> Path | None:
    matches = sorted(
        repo_root.glob(glob_pattern), key=lambda p: p.stat().st_mtime, reverse=True
    )
    return matches[0] if matches else None


def source_mtime(repo_root: Path) -> float:
    newest = 0.0
    for path in (repo_root / "linux" / "raofflineproxy").rglob("*.py"):
        newest = max(newest, path.stat().st_mtime)
    return newest


def build_bundle(device, repo_root: Path, force: bool = False) -> Path:
    override = os.environ.get("RAOP_E2E_INSTALLER")
    if override:
        installer = Path(override)
        if not installer.exists():
            raise BundleError("RAOP_E2E_INSTALLER points at a missing file: %s" % installer)
        return installer

    existing = newest_installer(repo_root, device.installer_glob)
    if existing is not None and not force and existing.stat().st_mtime >= source_mtime(repo_root):
        return existing

    completed = subprocess.run(
        ["bash", str(device.bundle_script)],
        cwd=str(repo_root),
        capture_output=True,
        text=True,
        timeout=1800,
    )
    if completed.returncode != 0:
        raise BundleError(
            "%s failed:\n%s\n%s"
            % (device.bundle_script, completed.stdout, completed.stderr)
        )

    installer = newest_installer(repo_root, device.installer_glob)
    if installer is None:
        raise BundleError("no installer matched %s after build" % device.installer_glob)
    return installer
