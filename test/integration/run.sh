#!/usr/bin/env bash
#
# Runs the compiled bin/cache-clean.js against a Redis container, and checks
# that the keys of both the legacy (Cm_Cache_Backend_Redis) and the Symfony
# (RedisTagAdapter) layout are removed.
#
# The seeded keys follow the layouts as implemented in src/cache/storage/redis.cljs.
# They are not created by a real Magento or Mage-OS instance.
#
# Usage: make integration-test

set -euo pipefail

IMAGE="${IMAGE:-magento-cache-clean-build}"
# Pinned by digest, so a changed tag cannot alter the test environment.
REDIS_IMAGE="redis:7-alpine@sha256:7f7d30b7fdd09467bac58198a1f166dd69789d348d4fe859cccb20e0cf287fbe"

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
NAME="cache-clean-test-$$"
FAILED=0

cleanup() {
    docker rm --force "$NAME" > /dev/null 2>&1 || true
    docker network rm "$NAME" > /dev/null 2>&1 || true
}
trap cleanup EXIT

redis() {
    docker exec "$NAME" redis-cli "$@"
}

cache_clean() {
    docker run --rm \
        --network "$NAME" \
        --read-only \
        --user "$(id -u):$(id -g)" \
        --volume "$ROOT":/app:ro \
        "$IMAGE" node bin/cache-clean.js --silent -d test/integration/magento "$@"
}

seed() {
    redis -n 0 FLUSHALL > /dev/null
    # Legacy layout
    redis -n 0 MSET zc:k:t_A 1 zc:k:t_KEEP 1 zc:k:t_CRONTAB_CONFIG_CACHE 1 > /dev/null
    redis -n 0 SADD zc:ids t_A t_KEEP t_CRONTAB_CONFIG_CACHE > /dev/null
    redis -n 0 SADD zc:tags t_CONFIG > /dev/null
    redis -n 0 SADD zc:ti:t_CONFIG t_A > /dev/null
    # Symfony layout
    redis -n 0 MSET t_:B 1 t_:KEEP 1 t_:CRONTAB_CONFIG_CACHE 1 > /dev/null
    redis -n 0 SADD cache:id_tags:t_B CONFIG > /dev/null
    redis -n 0 SADD cache:id_tags:t_CRONTAB_CONFIG_CACHE OTHER > /dev/null
    redis -n 0 SADD cache:tags:t_CONFIG B > /dev/null
    # Full page cache
    redis -n 1 SET fpc 1 > /dev/null
}

# expect <description> <expected> <redis-cli args...>
expect() {
    local description="$1" expected="$2" actual
    shift 2
    actual="$(redis "$@")"
    if [ "$actual" = "$expected" ]; then
        echo "  ok    $description"
    else
        echo "  FAIL  $description: expected $expected, got $actual (redis-cli $*)"
        FAILED=1
    fi
}

docker network create "$NAME" > /dev/null
docker run --detach --name "$NAME" --network "$NAME" --network-alias redis "$REDIS_IMAGE" > /dev/null

for _ in $(seq 1 50); do
    redis PING > /dev/null 2>&1 && break
    sleep 0.2
done
redis PING > /dev/null

echo "Clean cache type: config"
seed
cache_clean config
expect "legacy tagged id removed"         0 -n 0 EXISTS zc:k:t_A
expect "legacy tag index removed"         0 -n 0 EXISTS zc:ti:t_CONFIG
expect "legacy id removed from id set"    0 -n 0 SISMEMBER zc:ids t_A
expect "legacy tag removed from tag set"  0 -n 0 SISMEMBER zc:tags t_CONFIG
expect "symfony tagged id removed"        0 -n 0 EXISTS t_:B
expect "symfony id tags removed"          0 -n 0 EXISTS cache:id_tags:t_B
expect "symfony tag index removed"        0 -n 0 EXISTS cache:tags:t_CONFIG
expect "untagged ids kept"                4 -n 0 EXISTS zc:k:t_KEEP t_:KEEP zc:k:t_CRONTAB_CONFIG_CACHE t_:CRONTAB_CONFIG_CACHE
expect "full page cache kept"             1 -n 1 EXISTS fpc

echo "Clean cache id from file list: crontab.xml"
seed
cache_clean --file-list test/integration/file-list.txt
expect "legacy id removed"                0 -n 0 EXISTS zc:k:t_CRONTAB_CONFIG_CACHE
expect "legacy id removed from id set"    0 -n 0 SISMEMBER zc:ids t_CRONTAB_CONFIG_CACHE
expect "symfony id removed"               0 -n 0 EXISTS t_:CRONTAB_CONFIG_CACHE
expect "symfony id tags removed"          0 -n 0 EXISTS cache:id_tags:t_CRONTAB_CONFIG_CACHE
expect "other ids kept"                   4 -n 0 EXISTS zc:k:t_KEEP t_:KEEP zc:k:t_A t_:B
expect "full page cache kept"             1 -n 1 EXISTS fpc

echo "Clean cache type: full_page"
seed
cache_clean full_page
expect "full page cache flushed"          0 -n 1 DBSIZE
expect "default cache kept"               2 -n 0 EXISTS zc:k:t_KEEP t_:KEEP

echo "Flush all caches"
seed
cache_clean
expect "default cache flushed"            0 -n 0 DBSIZE
expect "full page cache flushed"          0 -n 1 DBSIZE

if [ "$FAILED" -ne 0 ]; then
    echo "Integration tests failed"
    exit 1
fi
echo "Integration tests passed"
