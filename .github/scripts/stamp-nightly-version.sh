#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -lt 2 ]; then
  echo "usage: $0 <base-version> <pathspec>..." >&2
  exit 1
fi

BASE_VERSION="$1"
shift
NIGHTLY_VERSION="${BASE_VERSION}-nightly.${GITHUB_RUN_NUMBER}"
BASE_PATTERN="${BASE_VERSION//./\\.}"

git grep -lF "${BASE_VERSION}" -- "$@" | while read -r file; do
  sed -i "s/${BASE_PATTERN}\b/${NIGHTLY_VERSION}/g" "${file}"
  echo "Stamped ${file}"
done

echo "version=${NIGHTLY_VERSION}" >> "${GITHUB_OUTPUT}"
