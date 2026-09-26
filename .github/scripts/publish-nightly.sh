#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -lt 4 ]; then
  echo "usage: $0 <tag> <platform> <version> <asset>..." >&2
  exit 1
fi

if [ -z "${GH_TOKEN:-}" ] || [ -z "${NIGHTLY_REPO:-}" ]; then
  echo "::error::GH_TOKEN (NIGHTLY_REPO_TOKEN secret) and NIGHTLY_REPO must be set"
  exit 1
fi

TAG="$1"
PLATFORM="$2"
VERSION="$3"
TITLE="${PLATFORM} ${VERSION}"
shift 3

NOTES="> [!WARNING]
> Untested development build, expect bugs. For the stable version use ${GITHUB_SERVER_URL}/${GITHUB_REPOSITORY}/releases

Automated nightly build \`${VERSION}\` of ${GITHUB_SERVER_URL}/${GITHUB_REPOSITORY}/commit/${GITHUB_SHA}

Nightly installs are offered newer nightlies and newer stable releases through the in-app update check."

if ! gh release view "${TAG}" --repo "${NIGHTLY_REPO}" >/dev/null 2>&1; then
  gh release create "${TAG}" "$@" --repo "${NIGHTLY_REPO}" --prerelease --latest=false --title "${TITLE}" --notes "${NOTES}"
  exit 0
fi

gh release view "${TAG}" --repo "${NIGHTLY_REPO}" --json assets --jq '.assets[].name' | while read -r asset; do
  gh release delete-asset "${TAG}" "${asset}" --repo "${NIGHTLY_REPO}" --yes
done
gh release upload "${TAG}" "$@" --repo "${NIGHTLY_REPO}"
gh release edit "${TAG}" --repo "${NIGHTLY_REPO}" --prerelease --latest=false --title "${TITLE}" --notes "${NOTES}"
