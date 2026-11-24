#!/usr/bin/env bash
#
# Starts the compiled bin/cache-clean.js in watch mode in a Linux container, and
# checks that it notices every save of a file, for the different ways editors
# and git write files. See test/integration/watcher/check.sh
#
# Usage: make integration-test

set -euo pipefail

IMAGE="${IMAGE:-magento-cache-clean-build}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"

docker run --rm \
    --network none \
    --read-only \
    --tmpfs /tmp:mode=1777 \
    --user "$(id -u):$(id -g)" \
    --volume "$ROOT":/app:ro \
    "$IMAGE" sh test/integration/watcher/check.sh
