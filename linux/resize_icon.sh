#!/usr/bin/env bash
# Resizes a PNG to an exact square, used by the device bundles that ship their
# own launcher icon.
#
# sips is macOS-only, so a bundle built on Linux (CI) needs a fallback. Both
# ImageMagick spellings are tried; Python/Pillow is last because it is the one
# dependency that may not be installed.
#
# Usage:
#   linux/resize_icon.sh <source.png> <dest.png> <size>
set -euo pipefail

SOURCE="${1:?source image required}"
DEST="${2:?destination path required}"
SIZE="${3:?size in pixels required}"

mkdir -p "$(dirname "${DEST}")"

if command -v sips >/dev/null 2>&1; then
  sips -z "${SIZE}" "${SIZE}" "${SOURCE}" --out "${DEST}" >/dev/null
elif command -v magick >/dev/null 2>&1; then
  magick "${SOURCE}" -resize "${SIZE}x${SIZE}!" "${DEST}"
elif command -v convert >/dev/null 2>&1; then
  convert "${SOURCE}" -resize "${SIZE}x${SIZE}!" "${DEST}"
elif python3 -c "import PIL" >/dev/null 2>&1; then
  python3 - "${SOURCE}" "${DEST}" "${SIZE}" <<'PY'
import sys

from PIL import Image

source, dest, size = sys.argv[1], sys.argv[2], int(sys.argv[3])
Image.open(source).resize((size, size), Image.LANCZOS).save(dest)
PY
else
  echo "No image resizer found (need sips, magick, convert, or Pillow)." >&2
  exit 1
fi
