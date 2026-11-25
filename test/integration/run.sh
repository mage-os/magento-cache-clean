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
    docker rm --force "$NAME" "$NAME-varnish" > /dev/null 2>&1 || true
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
        "$IMAGE" ${TIMEOUT:-} node bin/cache-clean.js --silent -d "${MAGENTO:-test/integration/magento}" "$@"
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

# Internal network, the containers only need to reach each other
docker network create --internal "$NAME" > /dev/null
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

echo "Clean cache id from file list, short option: -f"
seed
cache_clean -f test/integration/file-list.txt
expect "legacy id removed"                0 -n 0 EXISTS zc:k:t_CRONTAB_CONFIG_CACHE
expect "symfony id removed"               0 -n 0 EXISTS t_:CRONTAB_CONFIG_CACHE
expect "other ids kept"                   4 -n 0 EXISTS zc:k:t_KEEP t_:KEEP zc:k:t_A t_:B

# The default cache as on Adobe Commerce Cloud: a remote redis backend with a
# local file backend. Magento stores a version hash for each id in redis.
seed_version_hashes() {
    redis -n 0 MSET zc:k:t_A:hash 1 zc:k:t_KEEP:hash 1 zc:k:t_CRONTAB_CONFIG_CACHE:hash 1 > /dev/null
    redis -n 0 SADD zc:ids t_A:hash t_KEEP:hash t_CRONTAB_CONFIG_CACHE:hash > /dev/null
    redis -n 0 SADD zc:ti:t_CONFIG t_A:hash > /dev/null
}

echo "RemoteSynchronizedCache, clean cache type: config"
seed
seed_version_hashes
MAGENTO=test/integration/magento-remote-synchronized cache_clean config
expect "tagged id and version hash removed" 0 -n 0 EXISTS zc:k:t_A zc:k:t_A:hash
expect "tag index removed"                0 -n 0 EXISTS zc:ti:t_CONFIG
expect "untagged ids kept"                4 -n 0 EXISTS zc:k:t_KEEP zc:k:t_KEEP:hash zc:k:t_CRONTAB_CONFIG_CACHE zc:k:t_CRONTAB_CONFIG_CACHE:hash
expect "full page cache kept"             1 -n 1 EXISTS fpc

echo "RemoteSynchronizedCache, clean cache id from file list: crontab.xml"
seed
seed_version_hashes
MAGENTO=test/integration/magento-remote-synchronized cache_clean --file-list test/integration/file-list.txt
expect "id and version hash removed"      0 -n 0 EXISTS zc:k:t_CRONTAB_CONFIG_CACHE zc:k:t_CRONTAB_CONFIG_CACHE:hash
expect "id removed from id set"           0 -n 0 SISMEMBER zc:ids t_CRONTAB_CONFIG_CACHE
expect "version hash removed from id set" 0 -n 0 SISMEMBER zc:ids t_CRONTAB_CONFIG_CACHE:hash
expect "other ids kept"                   4 -n 0 EXISTS zc:k:t_KEEP zc:k:t_KEEP:hash zc:k:t_A zc:k:t_A:hash

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

# expect_failure <description> <expected output> <cache-clean.js args...>
# The error has to be reported and the exit code has to be 1, without
# an unhandled error and without waiting for the timeout.
expect_failure() {
    local description="$1" expected="$2" output code
    shift 2
    set +e
    output="$(TIMEOUT="timeout 20" cache_clean "$@" 2>&1)"
    code=$?
    set -e
    if [ "$code" -eq 1 ] && [[ "$output" == *"$expected"* ]] && [[ "$output" != *"Unhandled"* ]]; then
        echo "  ok    $description"
    else
        echo "  FAIL  $description: expected \"$expected\" and exit code 1, got exit code $code"
        echo "$output"
        FAILED=1
    fi
}

expect_failures() {
    local expected="$1"
    expect_failure "clean cache type"       "$expected" config
    expect_failure "clean id from file list" "$expected" --file-list test/integration/file-list.txt
    expect_failure "clean full page cache"  "$expected" full_page
    expect_failure "flush all caches"       "$expected"
}

echo "A password is configured, but redis requires none"
seed
MAGENTO=test/integration/magento-wrong-password expect_failures "ERR AUTH"
expect "nothing was removed"              12 -n 0 DBSIZE
expect "full page cache kept"             1 -n 1 EXISTS fpc

echo "The configured redis database does not exist"
seed
MAGENTO=test/integration/magento-wrong-database expect_failures "DB index is out of range"
expect "nothing was removed"              12 -n 0 DBSIZE
expect "full page cache kept"             1 -n 1 EXISTS fpc

echo "Varnish answers the PURGE request with a plain 200 response"
seed
docker run --detach --name "$NAME-varnish" --network "$NAME" --network-alias varnish \
    --read-only --user "$(id -u):$(id -g)" --volume "$ROOT":/app:ro \
    "$IMAGE" node test/integration/varnish.js > /dev/null
for _ in $(seq 1 50); do
    docker logs "$NAME-varnish" 2>&1 | grep -q . && break
    docker exec "$NAME-varnish" node -e 'require("http").get("http://localhost:6081")' > /dev/null 2>&1 || true
    sleep 0.2
done
output="$(MAGENTO=test/integration/magento-varnish cache_clean --verbosity 1 full_page 2>&1)"
purges="$(docker logs "$NAME-varnish" 2>&1 | grep -c '^PURGE \.\*$' || true)"
if [ "$purges" -eq 1 ] && [[ "$output" != *"Varnish response code"* ]]; then
    echo "  ok    purge request sent and response accepted"
else
    echo "  FAIL  expected one PURGE request and no complaint about the response, got $purges request(s)"
    echo "$output"
    FAILED=1
fi
expect "full page cache flushed"          0 -n 1 DBSIZE
docker rm --force "$NAME-varnish" > /dev/null

echo "Redis rejects the commands"
seed
redis ACL SETUSER default -del -srem -flushdb > /dev/null
expect_failures "NOPERM"
redis ACL SETUSER default +@all > /dev/null
expect "nothing was removed"              12 -n 0 DBSIZE

echo "Redis accepts the connection but does not answer"
docker pause "$NAME" > /dev/null
expect_failure "flush all caches"       "Redis is not ready after" 
docker unpause "$NAME" > /dev/null

echo "Redis requires a password that is not configured"
seed
redis CONFIG SET requirepass secret > /dev/null
expect_failures "NOAUTH"

echo "Redis refuses the connection"
docker rm --force "$NAME" > /dev/null
# The host name still resolves, but nothing listens on the redis port
docker run --detach --name "$NAME" --network "$NAME" --network-alias redis "$REDIS_IMAGE" sleep 600 > /dev/null
expect_failures "ECONNREFUSED"

echo "Redis host name does not resolve"
docker rm --force "$NAME" > /dev/null
expect_failures "Redis connection to redis:6379 failed"

if [ "$FAILED" -ne 0 ]; then
    echo "Integration tests failed"
    exit 1
fi
echo "Integration tests passed"
