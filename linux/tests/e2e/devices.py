from __future__ import annotations

from pathlib import Path

E2E_DIR = Path(__file__).resolve().parent
REPO_ROOT = E2E_DIR.parents[2]


class Device:
    def __init__(
        self,
        name: str,
        platform: str,
        dockerfile: str,
        bundle_script: str,
        installer_glob: str,
        install_mode: str,
        base_dir: str,
        bin_path: str,
        uninstall_path: str,
        config_dir: str,
        retroarch_cfg: str,
        batocera_conf: str | None,
        ppsspp_ini: str | None,
        dolphin_ini: str | None,
        boot_hook: str,
        tools_entry: str | None,
        python_bin: str = "/usr/bin/python3",
        rom_dir: str = "/userdata/roms/snes",
        install_dest: str | None = None,
        build_args: dict | None = None,
        cli_style: str = "launcher",
        proxy_port: int = 8080,
        residue_paths: tuple = (),
    ) -> None:
        self.name = name
        self.platform = platform
        self.dockerfile = E2E_DIR / dockerfile
        self.bundle_script = REPO_ROOT / bundle_script
        self.installer_glob = installer_glob
        self.install_mode = install_mode
        self.base_dir = base_dir
        self.bin_path = bin_path
        self.uninstall_path = uninstall_path
        self.config_dir = config_dir
        self.retroarch_cfg = retroarch_cfg
        self.batocera_conf = batocera_conf
        self.ppsspp_ini = ppsspp_ini
        self.dolphin_ini = dolphin_ini
        self.boot_hook = boot_hook
        self.tools_entry = tools_entry
        self.python_bin = python_bin
        self.rom_dir = rom_dir
        self.install_dest = install_dest
        self.build_args = dict(build_args or {})
        self.cli_style = cli_style
        self.proxy_port = proxy_port
        self.residue_paths = residue_paths

    @property
    def proxy_value(self) -> str:
        return "127.0.0.1:%d" % self.proxy_port

    @property
    def image_tag(self) -> str:
        return "raop-e2e-%s:latest" % self.name

    def __repr__(self) -> str:
        return "Device(%s)" % self.name


KNULLI = Device(
    name="knulli",
    platform="linux/arm64",
    dockerfile="rootfs/Dockerfile.knulli",
    bundle_script="linux/knulli/build_bundle.sh",
    installer_glob="linux/knulli/dist/RAOfflineProxy-Knulli-v*-Install.sh",
    install_mode="self-extracting",
    base_dir="/userdata/system/raofflineproxy",
    bin_path="/userdata/system/raofflineproxy/bin/raofflineproxy",
    uninstall_path="/userdata/system/raofflineproxy/bin/raofflineproxy-uninstall",
    config_dir="/userdata/system/.config/raofflineproxy",
    retroarch_cfg="/userdata/system/configs/retroarch/retroarchcustom.cfg",
    batocera_conf="/userdata/system/batocera.conf",
    ppsspp_ini=None,
    dolphin_ini=None,
    boot_hook="/userdata/system/custom.sh",
    tools_entry="/userdata/roms/tools/RAOfflineProxy.sh",
    residue_paths=(
        "/userdata/system/raofflineproxy",
        "/userdata/system/raofflineproxy-knulli-bundle",
        "/userdata/system/.config/raofflineproxy",
        "/userdata/roms/tools/RAOfflineProxy.sh",
    ),
)

ROCKNIX = Device(
    name="rocknix",
    platform="linux/arm64",
    dockerfile="rootfs/Dockerfile.rocknix",
    bundle_script="linux/rocknix/build_bundle.sh",
    installer_glob="linux/rocknix/dist/RAOfflineProxy-Rocknix-v*-Install.sh",
    install_mode="self-extracting",
    base_dir="/storage/.local/share/raofflineproxy",
    bin_path="/storage/.local/share/raofflineproxy/bin/raofflineproxy",
    uninstall_path="/storage/.local/share/raofflineproxy/bin/raofflineproxy-uninstall",
    config_dir="/storage/.config/raofflineproxy",
    retroarch_cfg="/storage/.config/retroarch/retroarch.cfg",
    # ROCKNIX has no batocera.conf; system.cfg carries the identical
    # global.retroachievements keys and is what setsettings.sh reads.
    batocera_conf="/storage/.config/system/configs/system.cfg",
    ppsspp_ini="/storage/.config/ppsspp/PSP/SYSTEM/ppsspp.ini",
    dolphin_ini="/storage/.config/dolphin-emu/RetroAchievements.ini",
    boot_hook="/storage/.config/autostart/raofflineproxy.sh",
    tools_entry="/storage/.config/modules/RAOfflineProxy.sh",
    rom_dir="/storage/roms/snes",
    residue_paths=(
        "/storage/.local/share/raofflineproxy",
        "/storage/.local/share/.raofflineproxy-rocknix-bundle",
        "/storage/.config/raofflineproxy",
        "/storage/.config/modules/RAOfflineProxy.sh",
        "/storage/.config/autostart/raofflineproxy.sh",
    ),
)

MUOS = Device(
    name="muos",
    platform="linux/arm64",
    dockerfile="rootfs/Dockerfile.muos",
    bundle_script="linux/muos/build_bundle.sh",
    installer_glob="linux/muos/dist/RAOfflineProxy-muOS-v*.muxapp",
    # .muxapp is a zip that muOS's Archive Manager unpacks into application/.
    install_mode="muxapp",
    install_dest="/run/muos/storage/application",
    base_dir="/run/muos/storage/application/RAOfflineProxy",
    bin_path="/run/muos/storage/application/RAOfflineProxy/launch.sh",
    uninstall_path="/run/muos/storage/application/RAOfflineProxy/uninstall.sh",
    # launch.sh exports RAOFFLINEPROXY_CONFIG_DIR at this path rather than
    # letting resolve_config_dir() find it.
    config_dir="/run/muos/storage/application/RAOfflineProxy/data",
    retroarch_cfg="/opt/muos/share/info/config/retroarch.cfg",
    # muOS ships no batocera.conf; /opt/muos/script/archive suppresses it.
    batocera_conf=None,
    ppsspp_ini=None,
    dolphin_ini=None,
    boot_hook="/run/muos/storage/init/raofflineproxy.sh",
    tools_entry=None,
    rom_dir="/mnt/mmc/ROMS/snes",
    residue_paths=(
        "/run/muos/storage/application/RAOfflineProxy",
        "/run/muos/storage/init/raofflineproxy.sh",
    ),
)

def _miyoo(
    name: str,
    app_dir: str,
    retroarch_cfg: str,
    boot_hook: str,
    extra_residue=(),
    proxy_port: int = 8080,
):
    """Onion, spruce and Allium: same armv7 hardware, same /mnt/SDCARD layout,
    same bundled CPython 3.9 runtime. Only markers and paths differ."""
    return Device(
        name=name,
        platform="linux/arm/v7",
        dockerfile="rootfs/Dockerfile.miyoo",
        build_args={"FIRMWARE": name},
        bundle_script="linux/%s/build_bundle.sh" % name,
        installer_glob="linux/%s/dist/RAOfflineProxy-*-v*.zip" % name,
        install_mode="sdcard-zip",
        install_dest="/mnt/SDCARD",
        cli_style="common-sh",
        base_dir=app_dir,
        bin_path=app_dir + "/launch.sh",
        # No in-app uninstaller on the Miyoo firmwares; the menu reports
        # "Uninstall is not available on this platform" and users delete the
        # folder from the card themselves.
        uninstall_path=None,
        config_dir=app_dir + "/data",
        retroarch_cfg=retroarch_cfg,
        batocera_conf=None,
        ppsspp_ini=None,
        dolphin_ini=None,
        boot_hook=boot_hook,
        tools_entry=None,
        rom_dir="/mnt/SDCARD/Roms/SFC",
        residue_paths=(app_dir,) + tuple(extra_residue),
        proxy_port=proxy_port,
    )


ONION = _miyoo(
    "onion",
    "/mnt/SDCARD/App/RAOfflineProxy",
    "/mnt/SDCARD/RetroArch/.retroarch/retroarch.cfg",
    "/mnt/SDCARD/.tmp_update/startup/raofflineproxy.sh",
    extra_residue=("/mnt/SDCARD/.tmp_update/checkoff/raofflineproxy.sh",),
)

SPRUCE = _miyoo(
    "spruce",
    "/mnt/SDCARD/App/RAOfflineProxy",
    # spruce launches RetroArch with --config pointing at a per-device file, so
    # .retroarch/retroarch.cfg is never read.
    "/mnt/SDCARD/RetroArch/platform/retroarch-MiyooMini.cfg",
    # Same path Allium uses; platform.py dispatches on running_on_allium().
    "/mnt/SDCARD/.tmp_update/updater",
    # spruce ships SFTPGo bound to 0.0.0.0:8080, so the proxy moves to 8099.
    proxy_port=8099,
)

ALLIUM = _miyoo(
    "allium",
    "/mnt/SDCARD/Apps/RAOfflineProxy.pak",
    "/mnt/SDCARD/RetroArch/.retroarch/retroarch.cfg",
    "/mnt/SDCARD/.tmp_update/updater",
)

DEVICES = {
    device.name: device
    for device in (KNULLI, ROCKNIX, MUOS, ONION, SPRUCE, ALLIUM)
}
