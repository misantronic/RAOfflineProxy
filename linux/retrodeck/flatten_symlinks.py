from __future__ import annotations

from pathlib import Path
import shutil
import sys


def flatten_symlink(path: Path) -> None:
    target = path.resolve(strict=True)
    path.unlink()
    if target.is_dir():
        shutil.copytree(target, path, symlinks=False)
        return
    shutil.copy2(target, path)


def main() -> int:
    if len(sys.argv) != 2:
        raise SystemExit("usage: flatten_symlinks.py <root>")

    root = Path(sys.argv[1])
    symlinks = sorted(
        (path for path in root.rglob("*") if path.is_symlink()),
        key=lambda candidate: len(candidate.parts),
    )

    for symlink in symlinks:
        flatten_symlink(symlink)

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
