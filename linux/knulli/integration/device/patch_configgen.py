#!/usr/bin/env python3
"""Apply/revert the RAOfflineProxy dev hook in Knulli's configgen libretroConfig.py.

The hook is inserted between markers so it is idempotent and can be stripped
cleanly. A pristine backup (libretroConfig.py.raop-orig) is kept for revert.
"""

import argparse
import glob
import importlib.util
import shutil
import sys
from pathlib import Path

MARKER_BEGIN = "# >>> RAOfflineProxy dev integration"
MARKER_END = "# <<< RAOfflineProxy dev integration"
ANCHOR = "if system.isOptSet('integerscale')"
BACKUP_SUFFIX = ".raop-orig"

HOOK_BLOCK = """\
    # >>> RAOfflineProxy dev integration
    try:
        raop_port = 8080
        try:
            import json as raop_json
            with open('/userdata/system/.config/raofflineproxy/config.json') as raop_fh:
                raop_port = int(raop_json.load(raop_fh).get('proxy_port', 8080))
        except Exception:
            pass
        import socket as raop_socket
        raop_sock = raop_socket.socket(raop_socket.AF_INET, raop_socket.SOCK_STREAM)
        raop_sock.settimeout(0.25)
        raop_running = raop_sock.connect_ex(('127.0.0.1', raop_port)) == 0
        raop_sock.close()
        raop_wanted = system.isOptSet('retroachievements') and system.getOptBoolean('retroachievements') == True
        raop_core_ok = (system.config['core'] in coreToRetroachievements) or (system.isOptSet('cheevos_force') and system.getOptBoolean('cheevos_force') == True)
        raop_proxy_on = (not system.isOptSet('retroachievements.proxy')) or (system.getOptBoolean('retroachievements.proxy') == True)
        if raop_running and raop_wanted and raop_core_ok and raop_proxy_on:
            retroarchConfig['cheevos_enable'] = 'true'
            retroarchConfig['cheevos_custom_host'] = '127.0.0.1:%s' % raop_port
            retroarchConfig['cheevos_hardcore_mode_enable'] = 'false'
        else:
            retroarchConfig['cheevos_custom_host'] = ''
    except Exception:
        retroarchConfig['cheevos_custom_host'] = ''
    # <<< RAOfflineProxy dev integration

"""


def find_target() -> Path:
    spec = importlib.util.find_spec("configgen")
    if spec and spec.origin:
        candidate = Path(spec.origin).parent / "generators" / "libretro" / "libretroConfig.py"
        if candidate.is_file():
            return candidate

    patterns = [
        "/usr/lib/python3*/site-packages/configgen/generators/libretro/libretroConfig.py",
        "/usr/lib/python3*/dist-packages/configgen/generators/libretro/libretroConfig.py",
    ]
    for pattern in patterns:
        matches = sorted(glob.glob(pattern))
        if matches:
            return Path(matches[0])

    raise FileNotFoundError("configgen libretroConfig.py not found on this system")


def clear_pycache(target: Path) -> None:
    pycache = target.parent / "__pycache__"
    if pycache.is_dir():
        shutil.rmtree(pycache, ignore_errors=True)


def backup_path(target: Path) -> Path:
    return target.with_name(target.name + BACKUP_SUFFIX)


def do_apply(target: Path) -> int:
    text = target.read_text()

    if MARKER_BEGIN in text:
        print(f"already patched: {target}")
        return 0

    lines = text.splitlines(keepends=True)
    anchor_index = next(
        (i for i, line in enumerate(lines) if line.lstrip().startswith(ANCHOR)),
        None,
    )
    if anchor_index is None:
        print(f"ERROR: anchor not found in {target}; configgen layout changed, not patching", file=sys.stderr)
        return 2

    backup = backup_path(target)
    if not backup.exists():
        shutil.copy2(target, backup)

    lines.insert(anchor_index, HOOK_BLOCK)
    target.write_text("".join(lines))
    clear_pycache(target)
    print(f"patched: {target}")
    print(f"backup:  {backup}")
    return 0


def do_revert(target: Path) -> int:
    backup = backup_path(target)

    if backup.exists():
        backup_text = backup.read_text()
        if MARKER_BEGIN in backup_text:
            print(f"ERROR: backup {backup} itself contains the hook; refusing to restore it", file=sys.stderr)
            return 2
        shutil.copy2(backup, target)
        backup.unlink()
        clear_pycache(target)
        print(f"reverted from backup: {target}")
        return 0

    text = target.read_text()
    if MARKER_BEGIN not in text:
        print(f"already clean: {target}")
        return 0

    lines = text.splitlines(keepends=True)
    begin = next(i for i, line in enumerate(lines) if MARKER_BEGIN in line)
    end = next(i for i, line in enumerate(lines) if MARKER_END in line)
    del lines[begin : end + 1]
    if begin < len(lines) and lines[begin].strip() == "":
        del lines[begin]
    target.write_text("".join(lines))
    clear_pycache(target)
    print(f"reverted by stripping markers: {target}")
    return 0


def do_status(target: Path) -> int:
    text = target.read_text()
    patched = MARKER_BEGIN in text
    backup = backup_path(target)
    print(f"file:    {target}")
    print(f"patched: {'yes' if patched else 'no'}")
    print(f"backup:  {backup if backup.exists() else 'none'}")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["apply", "revert", "status"])
    parser.add_argument("--file", help="Override path to libretroConfig.py")
    args = parser.parse_args()

    target = Path(args.file) if args.file else find_target()
    if not target.is_file():
        print(f"ERROR: {target} does not exist", file=sys.stderr)
        return 2

    if args.action == "apply":
        return do_apply(target)
    if args.action == "revert":
        return do_revert(target)
    return do_status(target)


if __name__ == "__main__":
    sys.exit(main())
