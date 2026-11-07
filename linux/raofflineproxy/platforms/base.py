from __future__ import annotations

from pathlib import Path

from . import boot_hooks

OS_RELEASE_PATH = Path("/etc/os-release")
# The standard Linux hardware-identity files (ARM devicetree, x86 DMI), the same pair
# ROCKNIX's own rocknix-info script uses to label a device.
DEVICETREE_MODEL_FILE = Path("/sys/firmware/devicetree/base/model")
DMI_SYS_VENDOR_FILE = Path("/sys/class/dmi/id/sys_vendor")
DMI_PRODUCT_NAME_FILE = Path("/sys/class/dmi/id/product_name")


def existing_dir(path: Path) -> Path | None:
    return path if path.is_dir() else None


def read_os_release() -> str:
    try:
        return OS_RELEASE_PATH.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return ""


def os_release_field(*keys: str) -> str | None:
    values: dict[str, str] = {}
    for line in read_os_release().splitlines():
        if "=" not in line:
            continue
        key, _, value = line.partition("=")
        values[key.strip()] = value.strip().strip('"')

    for key in keys:
        if values.get(key):
            return values[key]
    return None


def read_stripped(path: Path) -> str | None:
    try:
        # Devicetree properties are null-terminated C strings, hence the explicit
        # strip("\0"): plain .strip() only trims whitespace.
        text = path.read_text(encoding="utf-8", errors="replace").strip().strip("\0")
    except OSError:
        return None
    return text or None


def hardware_device_label(fallback: str) -> str:
    model = read_stripped(DEVICETREE_MODEL_FILE)
    if model:
        return model
    vendor = read_stripped(DMI_SYS_VENDOR_FILE)
    product = read_stripped(DMI_PRODUCT_NAME_FILE)
    combined = " ".join(part for part in (vendor, product) if part)
    return combined or fallback


class Platform:
    """One supported system. Everything that differs between systems belongs on a subclass,
    so the core never has to ask which system it runs on."""

    id: str = "generic"
    name: str = "Linux device"
    # What support tickets and usage reports call this system. Kept stable even where the
    # platform's own name differs ("OnionOS").
    support_label: str = "Linux"
    # The suffix of this platform's release asset ("RAOfflineProxy-<id>-v1.2.3.zip"), or None
    # where updates are not shipped through the app.
    update_asset_suffix: str | None = None

    def detect(self) -> bool:
        return False

    def startup_script(self) -> Path | None:
        """The file the boot hook is written to, or None where the platform has no boot hook."""
        return None

    def config_dir(self) -> Path | None:
        """Where settings, cached games and awards live, or None for the generic default."""
        return None

    def retroarch_cfg_candidates(self, config_dir: Path) -> tuple[Path, ...]:
        """retroarch.cfg locations, most specific first. The first one doubles as the fallback
        for a device that has not generated its cfg yet."""
        return ()

    def default_proxy_port(self) -> int | None:
        return None

    def batocera_conf(self, config_data: dict) -> str | None:
        """EmulationStation's own settings file, where the platform has one."""
        return None

    def secondary_retroarch_cfg(self, config_data: dict | None = None) -> str | None:
        """A second RetroArch build's cfg that needs the same patch as the primary one."""
        return None

    def ppsspp_ini(self) -> Path | None:
        return None

    def dolphin_config_dir(self) -> Path | None:
        return None

    def pcsx2_ini(self) -> Path | None:
        return None

    def duckstation_ini(self) -> Path | None:
        return None

    def flycast_cfg(self) -> Path | None:
        return None

    @property
    def update_channel(self) -> str | None:
        return self.id if self.update_asset_suffix else None

    def os_version(self) -> str | None:
        return os_release_field("OS_VERSION", "VERSION", "PRETTY_NAME", "VERSION_ID")

    def device_label(self) -> str:
        return hardware_device_label(self.support_label)

    def unsupported_reason(self) -> str | None:
        """Why this system cannot be supported (e.g. an old firmware build), or None."""
        return None

    def own_credentials(self) -> dict | None:
        """RetroAchievements credentials kept in the platform's own settings, the only source
        before a game launch copies them into the RetroArch config. Password only."""
        return None

    def remove_stale_files(self) -> None:
        """Deletes files an earlier release installed and no longer wants."""

    def install_service_unit(self) -> bool:
        """Installs the platform's service definition. Returns False where there is none."""
        return False

    def history_source(self) -> Path | None:
        """The platform's own recently-played store, when it keeps one."""
        return None

    def recent_rom_paths(self) -> list[Path] | None:
        """ROMs from history_source(), or None to fall back to RetroArch's content history."""
        return None

    def process_backend(self):
        """Module implementing process handling where the POSIX default does not work
        (Windows), or None."""
        return None

    def service_start(self, config_data: dict) -> dict | None:
        """Starts the service where something other than a detached process owns it (a
        systemd unit). None means the generic process start applies."""
        return None

    def service_stop(self) -> dict | None:
        return None

    def service_status(self) -> dict | None:
        return None

    def on_proxy_started(self, config_data: dict) -> None:
        """Called once the proxy listens, for platform-specific setup."""

    def on_proxy_stopped(self) -> None:
        """Called while the proxy shuts down, to undo what on_proxy_started did."""

    def preferred_rom_root(self, config_data: dict) -> Path | None:
        """A ROM library the platform knows for sure, used before consulting RetroArch's cfg."""
        return None

    def default_rom_root(self) -> Path | None:
        """Where ROMs live on this platform when neither the above nor the cfg say."""
        return None

    def autostart_supported(self, startup_script: Path | None) -> bool:
        return startup_script is not None

    def autostart_enabled(self, config_data: dict) -> bool | None:
        """The platform's own answer when something other than the config flag owns the state
        (a systemd unit, the registry), or None to use the flag."""
        return None

    def enable_autostart(self, config_data: dict) -> bool:
        """Returns True when the platform enabled autostart itself, with no boot hook file."""
        return False

    def disable_autostart(self, config_data: dict) -> bool:
        """Returns True when the platform disabled autostart itself."""
        return False

    def on_autostart_disabled(self) -> None:
        """Called after the config flag is cleared, for platforms with something to switch off."""

    def autostart_launcher(self, config_data: dict) -> str:
        configured = config_data.get("autostart_launcher")
        if configured:
            return str(configured)
        raise ValueError("autostart_launcher is not set and this platform has no default")

    def install_boot_hook(self, startup_script: Path, config_data: dict) -> None:
        boot_hooks.append_block(startup_script, boot_hooks.autostart_block(self.autostart_launcher(config_data)))

    def remove_boot_hook(self, startup_script: Path) -> None:
        boot_hooks.remove_block(startup_script)

    def boot_hook_installed(self, startup_script: Path) -> bool:
        return boot_hooks.has_block(boot_hooks.read_script(startup_script))
