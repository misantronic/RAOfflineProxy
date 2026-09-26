#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -lt 4 ]; then
  echo "usage: $0 <tag> <platform> <version> <asset>..." >&2
  exit 1
fi

TAG="$1"
PLATFORM="$2"
VERSION="$3"
TITLE="${PLATFORM} ${VERSION}"
shift 3

NOTES="Automated nightly build \`${VERSION}\` of ${GITHUB_SERVER_URL}/${GITHUB_REPOSITORY}/commit/${GITHUB_SHA}

Untested snapshot of \`main\`, expect bugs. Nightly installs are offered newer nightlies and newer stable releases through the in-app update check."

if ! gh release view "${TAG}" >/dev/null 2>&1; then
  gh release create "${TAG}" "$@" --target "${GITHUB_SHA}" --prerelease --latest=false --title "${TITLE}" --notes "${NOTES}"
  exit 0
fi

gh release view "${TAG}" --json assets --jq '.assets[].name' | while read -r asset; do
  gh release delete-asset "${TAG}" "${asset}" --yes
done
gh release upload "${TAG}" "$@"
gh release edit "${TAG}" --prerelease --latest=false --title "${TITLE}" --notes "${NOTES}"
