from __future__ import annotations

from pathlib import Path

AUTOSTART_SENTINEL_START = "# RAOfflineProxy autostart start"
AUTOSTART_SENTINEL_END = "# RAOfflineProxy autostart end"


def autostart_block(launcher: str) -> str:
    return "\n".join(
        [
            AUTOSTART_SENTINEL_START,
            f'if [ -x "{launcher}" ]; then',
            f"  {launcher} boot-reconcile >/dev/null 2>&1 || true",
            "fi",
            AUTOSTART_SENTINEL_END,
        ]
    )


def background_block(launcher: Path | str, unless_file: Path | str | None = None) -> str:
    """Backgrounded and guarded: a missing, slow or failing app must never delay or break
    the firmware's boot chain."""
    condition = f'[ -x "{launcher}" ]'
    if unless_file is not None:
        condition = f'[ ! -f "{unless_file}" ] && {condition}'
    return "\n".join(
        [
            AUTOSTART_SENTINEL_START,
            f"if {condition}; then",
            f'  sh "{launcher}" >/dev/null 2>&1 &',
            "fi",
            AUTOSTART_SENTINEL_END,
        ]
    )


def has_block(content: str) -> bool:
    return AUTOSTART_SENTINEL_START in content and AUTOSTART_SENTINEL_END in content


def strip_block(content: str) -> str:
    start = content.find(AUTOSTART_SENTINEL_START)
    if start < 0:
        return content

    end = content.find(AUTOSTART_SENTINEL_END, start)
    if end < 0:
        return content[:start]

    end += len(AUTOSTART_SENTINEL_END)
    return f"{content[:start]}{content[end:]}"


def read_script(script: Path) -> str:
    return script.read_text(encoding="utf-8", errors="replace") if script.exists() else ""


def append_block(script: Path, block: str) -> None:
    script.parent.mkdir(parents=True, exist_ok=True)
    cleaned = strip_block(read_script(script)).rstrip()
    script.write_text(f"{cleaned}\n\n{block}\n" if cleaned else f"{block}\n", encoding="utf-8")


def remove_block(script: Path) -> None:
    cleaned = strip_block(read_script(script)).strip()
    script.write_text(f"{cleaned}\n" if cleaned else "", encoding="utf-8")


def prepend_block(script: Path, block: str, firmware: str) -> None:
    """Inserts the block straight after the shebang, for boot scripts that end by dispatching
    into something that never returns, where an appended block would be dead code."""
    if not script.exists():
        raise ValueError(f"{firmware} boot script not found: {script}")

    existing = script.read_text(encoding="utf-8", errors="replace")
    if not existing.startswith("#!"):
        # Refuse rather than write into something that isn't the shell script we expect.
        raise ValueError(f"unrecognised {firmware} boot script, autostart not installed: {script}")

    cleaned = strip_block(existing)
    shebang, _, remainder = cleaned.partition("\n")
    # lstrip so repeated installs (every app launch) don't accumulate blank lines where
    # strip_block removed the previous copy.
    updated = f"{shebang}\n\n{block}\n\n{remainder.lstrip(chr(10))}"
    if updated != existing:
        script.write_text(updated, encoding="utf-8")
