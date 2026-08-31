from __future__ import annotations

import json
import shutil
import subprocess
import time
import uuid
from pathlib import Path


class DockerUnavailable(RuntimeError):
    pass


class ExecResult:
    def __init__(self, code: int, stdout: str, stderr: str) -> None:
        self.code = code
        self.stdout = stdout
        self.stderr = stderr

    @property
    def ok(self) -> bool:
        return self.code == 0

    def __repr__(self) -> str:
        return "ExecResult(code=%d, stdout=%r, stderr=%r)" % (
            self.code,
            self.stdout[:400],
            self.stderr[:400],
        )


def docker_available() -> bool:
    if shutil.which("docker") is None:
        return False
    probe = subprocess.run(
        ["docker", "info", "--format", "{{.ServerVersion}}"],
        capture_output=True,
        text=True,
    )
    return probe.returncode == 0


def run_docker(args: list[str], check: bool = True, timeout: int = 600) -> ExecResult:
    completed = subprocess.run(
        ["docker"] + args, capture_output=True, text=True, timeout=timeout
    )
    result = ExecResult(completed.returncode, completed.stdout, completed.stderr)
    if check and not result.ok:
        raise RuntimeError("docker %s failed: %s" % (" ".join(args[:2]), result.stderr))
    return result


def build_image(
    dockerfile: Path,
    context: Path,
    tag: str,
    platform: str,
    build_args: dict | None = None,
) -> str:
    args = ["build", "--platform", platform]
    for key, value in (build_args or {}).items():
        args += ["--build-arg", "%s=%s" % (key, value)]
    args += ["-f", str(dockerfile), "-t", tag, str(context)]
    run_docker(args, timeout=1800)
    return tag


class Container:
    def __init__(
        self,
        image: str,
        platform: str,
        publish: dict | None = None,
        mounts: dict | None = None,
        cap_add: tuple = (),
        name: str | None = None,
        privileged: bool = False,
        tmpfs: tuple = (),
        command: tuple = ("sleep", "infinity"),
    ) -> None:
        self.image = image
        self.platform = platform
        self.publish = dict(publish or {})
        self.mounts = dict(mounts or {})
        self.cap_add = tuple(cap_add)
        self.name = name or "raop-e2e-" + uuid.uuid4().hex[:10]
        # dArkOS manages the proxy through systemd, so its container boots a real
        # PID 1 instead of parking on `sleep infinity`. That needs --privileged,
        # a writable cgroup mount and tmpfs for /run.
        self.privileged = privileged
        self.tmpfs = tuple(tmpfs)
        self.command = tuple(command)
        self.container_id: str | None = None

    def start(self) -> None:
        args = ["run", "-d", "--platform", self.platform, "--name", self.name]
        if self.privileged:
            args += ["--privileged"]
        for path in self.tmpfs:
            args += ["--tmpfs", path]
        for capability in self.cap_add:
            args += ["--cap-add", capability]
        for container_port, host_port in self.publish.items():
            args += ["-p", "127.0.0.1:%s:%d" % (host_port or "", container_port)]
        for host_path, container_path in self.mounts.items():
            args += ["-v", "%s:%s" % (host_path, container_path)]
        args += [self.image] + list(self.command)
        self.container_id = run_docker(args).stdout.strip()
        self._wait_running()

    def wait_for_systemd(self, timeout: float = 120.0) -> None:
        """Block until PID 1 finishes booting.

        `degraded` is accepted: a container image has units that cannot start
        (no real hardware), and that is not a reason to fail the run.
        """
        deadline = time.time() + timeout
        state = "unknown"
        while time.time() < deadline:
            state = self.exec("systemctl is-system-running", timeout=30).stdout.strip()
            if state in ("running", "degraded"):
                return
            time.sleep(0.5)

        failed = self.exec(
            "systemctl list-units --failed --no-legend --no-pager", timeout=30
        ).stdout.strip()
        pending = self.exec(
            "systemctl list-jobs --no-legend --no-pager", timeout=30
        ).stdout.strip()
        raise RuntimeError(
            "systemd did not finish booting in %s after %ss (state=%r)\n"
            "failed units:\n%s\npending jobs:\n%s"
            % (self.name, timeout, state, failed or "(none)", pending or "(none)")
        )

    def _wait_running(self, timeout: float = 30.0) -> None:
        deadline = time.time() + timeout
        while time.time() < deadline:
            state = run_docker(
                ["inspect", "-f", "{{.State.Running}}", self.name], check=False
            )
            if state.ok and state.stdout.strip() == "true":
                return
            time.sleep(0.2)
        raise RuntimeError("container %s did not start" % self.name)

    def host_port(self, container_port: int) -> int:
        result = run_docker(["port", self.name, str(container_port)])
        return int(result.stdout.strip().splitlines()[0].rsplit(":", 1)[1])

    def exec(
        self,
        command: str,
        workdir: str | None = None,
        env: dict | None = None,
        check: bool = False,
        timeout: int = 300,
        shell: str = "/bin/sh",
        user: str | None = None,
    ) -> ExecResult:
        args = ["exec"]
        if user:
            args += ["-u", user]
        if workdir:
            args += ["-w", workdir]
        for key, value in (env or {}).items():
            args += ["-e", "%s=%s" % (key, value)]
        args += [self.name, shell, "-c", command]
        result = run_docker(args, check=False, timeout=timeout)
        if check and not result.ok:
            raise RuntimeError(
                "command failed in %s: %s\nstdout: %s\nstderr: %s"
                % (self.name, command, result.stdout, result.stderr)
            )
        return result

    def put(self, local: Path, remote: str) -> None:
        run_docker(["cp", str(local), "%s:%s" % (self.name, remote)])

    def get(self, remote: str, local: Path) -> None:
        run_docker(["cp", "%s:%s" % (self.name, remote), str(local)])

    def read_file(self, path: str) -> str:
        result = self.exec("cat %s" % _quote(path))
        if not result.ok:
            raise FileNotFoundError(path)
        return result.stdout

    def write_file(self, path: str, content: str) -> None:
        self.exec(
            "mkdir -p $(dirname %s) && cat > %s <<'RAOP_EOF'\n%s\nRAOP_EOF"
            % (_quote(path), _quote(path), content),
            check=True,
        )

    def exists(self, path: str) -> bool:
        return self.exec("test -e %s" % _quote(path)).ok

    def is_executable(self, path: str) -> bool:
        return self.exec("test -x %s" % _quote(path)).ok

    def listdir(self, path: str) -> list:
        result = self.exec("ls -1 %s 2>/dev/null" % _quote(path))
        return [line for line in result.stdout.splitlines() if line]

    def json_from(self, path: str) -> dict:
        return json.loads(self.read_file(path))

    def stop(self) -> None:
        if self.container_id is None:
            return
        run_docker(["rm", "-f", self.name], check=False, timeout=120)
        self.container_id = None

    def __enter__(self) -> "Container":
        self.start()
        return self

    def __exit__(self, *_exc) -> None:
        self.stop()


def _quote(value: str) -> str:
    return "'" + value.replace("'", "'\\''") + "'"
