from __future__ import annotations

import json


class AppCli:
    def __init__(self, container, device) -> None:
        self.container = container
        self.device = device

    def run(self, command: str, check: bool = False, timeout: int = 180):
        return self.container.exec(
            self._invocation(command), check=check, timeout=timeout
        )

    def _invocation(self, command: str) -> str:
        if self.device.cli_style == "common-sh":
            # Onion/spruce/Allium ship no CLI entry point: launch.sh goes
            # straight to the menu. common.sh is the product's own dispatcher,
            # so driving it exercises prepare_env and resolve_python_bin too.
            return (
                "cd %s && . ./common.sh && prepare_env >/dev/null 2>&1 && "
                'resolve_python_bin && run_backend_raw "$RESOLVED_PYTHON_BIN" %s'
                % (self.device.base_dir, command)
            )
        return "%s %s" % (self.device.bin_path, command)

    def json(self, command: str) -> dict:
        result = self.run(command, check=True)
        return json.loads(result.stdout)

    def service_running(self) -> bool:
        result = self.run("service-status")
        return "running: yes" in result.stdout

    def autostart_enabled(self) -> bool:
        result = self.run("autostart-status")
        return result.stdout.strip() == "enabled"

    def cached_game_count(self) -> int:
        result = self.run("cached-games-count", check=True)
        return int(result.stdout.strip().split()[-1])

    def pending_award_count(self) -> int:
        result = self.run("pending-awards-count", check=True)
        return int(result.stdout.strip().split()[-1])
