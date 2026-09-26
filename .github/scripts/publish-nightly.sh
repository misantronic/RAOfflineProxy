#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -lt 3 ]; then
  echo "usage: $0 <tag> <version> <asset>..." >&2
  exit 1
fi

TAG="$1"
VERSION="$2"
shift 2

NOTES="Automated nightly build \`${VERSION}\` of ${GITHUB_SERVER_URL}/${GITHUB_REPOSITORY}/commit/${GITHUB_SHA}

Untested snapshot of \`main\`, expect bugs. Nightly installs are offered newer nightlies and newer stable releases through the in-app update check."

if ! gh release view "${TAG}" >/dev/null 2>&1; then
  gh release create "${TAG}" "$@" --target "${GITHUB_SHA}" --prerelease --latest=false --title "${VERSION}" --notes "${NOTES}"
  exit 0
fi

gh release view "${TAG}" --json assets --jq '.assets[].name' | while read -r asset; do
  gh release delete-asset "${TAG}" "${asset}" --yes
done
gh release upload "${TAG}" "$@"
gh release edit "${TAG}" --prerelease --latest=false --title "${VERSION}" --notes "${NOTES}"
