#!/bin/sh
# Downloads the Embulk runtime jar used at compile/test time as a "provided"-scope
# dependency. embulk-core:0.9.12 (this module's original dependency) was only ever
# published to jcenter/bintray, both shut down, so it is no longer resolvable from
# Maven Central or any other live registry.
#
# v0.9.25 is the newest version that is still available as a public release asset on
# the upstream embulk/embulk repository, so it is used here instead of a newer patched
# version (no auth required, no private mirror dependency). The gap between v0.9.25 and
# later versions does not affect this module's build/test.
set -eu

EMBULK_VERSION="${EMBULK_VERSION:-0.9.25}"
OUT_DIR="$(cd "$(dirname "$0")/.." && pwd)/vendor"
OUT_FILE="${OUT_DIR}/embulk-${EMBULK_VERSION}.jar"

if [ -f "${OUT_FILE}" ]; then
    echo "Already present: ${OUT_FILE}"
    exit 0
fi

mkdir -p "${OUT_DIR}"

curl -fsSL \
    "https://github.com/embulk/embulk/releases/download/v${EMBULK_VERSION}/embulk-${EMBULK_VERSION}.jar" \
    -o "${OUT_FILE}.tmp"
mv "${OUT_FILE}.tmp" "${OUT_FILE}"
echo "Downloaded: ${OUT_FILE}"
