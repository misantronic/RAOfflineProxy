from __future__ import annotations

from pathlib import Path

from . import boot_hooks

OS_RELEASE_PATH = Path("/etc/os-release")
# The standard Linux hardware-identity files (ARM devicetree, x86 DMI), the same pair
# ROCKNIX's own rocknix-info script uses to label a device.
DEVICETREE_MODEL_FILE = Path("/sys/firmware/devicetree/base/model")
DMI_SYS_VENDOR_FILE = Path("/sys/class/dmi/id/sys_vendor")
DMI_PRODUCT_NAME_FILE = Path("/sys/class/dmi/id/product_name")
# Font files loaded by explicit path (SDL_ttf reads them directly, so no fontconfig is needed:
# ROCKNIX ships no fc-list, which makes pygame.font.match_font() return nothing). Each entry is
# (regular, bold); when a face has no dedicated bold file, bold is synthesized via set_bold.
SYSTEM_FONT_FILES = (
    (
        Path("/usr/share/fonts/liberation/LiberationMono-Regular.ttf"),
        Path("/usr/share/fonts/liberation/LiberationMono-Bold.ttf"),
    ),
    (
        Path("/usr/share/fonts/truetype/dejavu/DejaVuSansCondensed.ttf"),
        Path("/usr/share/fonts/truetype/dejavu/DejaVuSansCondensed.ttf"),
    ),
)


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
    # How an update is installed: "installer" runs the downloaded installer, "archive" swaps
    # the app directory from a zip. update_archive_root is where the app directory sits inside
    # the archive; an empty root means its top level.
    update_install_kind: str = "installer"
    update_archive_root: str = ""
    # Keep the app's data/ across the swap: None only within the same major version.
    preserve_app_data_on_update: bool | None = None
    delete_update_archive: bool = False
    # "bundled" ships the monospace font next to the menu, "system" looks for one on the device.
    menu_font: str = "default"
    # Whether the menu has to ask the user to map their confirm and cancel buttons.
    needs_controller_calibration: bool = True
    # Names the operating system in the User-Agent the proxy sends.
    user_agent_os: str = "Linux"
    # Windows lets a second listener bind over a live one with SO_REUSEADDR, so it must not.
    reuse_listen_address: bool = True

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

    def write_emulator_config(self, target: Path, content: str) -> None:
        target.write_text(content, encoding="utf-8")

    def adjust_config(self, config_data: dict) -> None:
        """Rewrites freshly loaded settings in place where the platform derives part of them
        from the machine (Windows points each emulator at its real config)."""

    def extra_log_files(self) -> list[Path]:
        """Files besides the service logs that belong in a support upload."""
        return []

    def metadata_overrides(self, config_data: dict) -> dict:
        """Device metadata that replaces what the generic probes report."""
        return {}

    def native_library_dirs(self) -> tuple[Path, ...]:
        """Directories to look in for the native zstd and crypto libraries."""
        return ()

    def watch_folder_prefix(self, folder: str) -> str:
        """The folder path as scanned file paths are prefixed with it."""
        return f"{folder.rstrip('/')}/"

    def is_test_build(self) -> bool:
        """A build that must stay out of the real usage numbers."""
        return False

    def on_award_synced(self, storage, award: dict) -> None:
        """Called after a queued award reached RetroAchievements."""

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

    def app_dir(self) -> Path | None:
        """Where the installed app lives, for platforms that update by swapping it."""
        return None

    def update_archive_path(self) -> Path | None:
        """Where the downloaded archive is written, or None for the default location."""
        return None

    def install_update(self, asset_url: str, progress) -> list[str]:
        """Downloads and installs a release asset, reporting each step through `progress`.
        Returns the command that starts the updated app."""
        from .. import update

        progress("Downloading update...")
        if self.update_install_kind != "archive":
            installer = update.download_update_installer(asset_url)
            progress("Installing update...")
            return [str(installer)]

        app_dir = self.app_dir()
        archive = update.download_update_archive(asset_url, self.update_archive_path())
        progress("Installing update...")
        update.install_archive_update(
            archive,
            app_dir,
            self.update_archive_root,
            preserve_data=self.preserve_app_data_on_update,
            delete_archive=self.delete_update_archive,
        )
        return ["/bin/sh", str(app_dir / "launch.sh"), "menu-sdl"]

    def uninstall_launcher(self, service_mode: bool) -> Path | None:
        """The script the menu's Uninstall entry runs, or None where it offers none."""
        return None

    def restart_frontend(self) -> None:
        """Brings the firmware's own launcher back after the menu exits."""

    def alternate_rom_root(self) -> Path | None:
        """A second ROM location the file browser can switch to."""
        return None

    def open_display(self, pygame, log):
        """The surface the menu draws on, for platforms whose display needs special setup.
        None means pygame's regular fullscreen window."""
        return None

    def reads_input_directly(self) -> bool:
        """Whether button presses come from /dev/input rather than pygame's key events."""
        return False

    def unsupported_notice(self) -> tuple[str, str] | None:
        """(heading, explanation) the menu shows instead of its functions when the system
        cannot be supported, or None."""
        return None

    def service_manager(self):
        """The firmware's own service manager when it owns the proxy where installed
        (status_label, active, start, stop, autostart_enabled, set_autostart), or None."""
        return None

    def menu_window_backend(self):
        """Module opening the menu window and reading input where /dev/input and a fixed
        framebuffer do not apply (Windows), or None."""
        return None

    def menu_session_class(self):
        """A MenuSdlSession subclass for this platform, or None."""
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
