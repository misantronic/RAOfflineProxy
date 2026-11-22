{ pkgs ? import <nixpkgs> {} }:

pkgs.mkShell {
  packages = with pkgs; [
    bash
    coreutils
    findutils
    gawk
    git
    python3
    python3Packages.pip
    unzip
    zip
    zig
  ];

  shellHook = ''
    export PIP_DISABLE_PIP_VERSION_CHECK=1
    export PYTHONDONTWRITEBYTECODE=1
  '';
}
