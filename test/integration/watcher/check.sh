#!/bin/sh
#
# Runs inside the container, see test/integration/watcher.sh
#
# Saves a template several times with each save pattern, and checks that the
# watcher processes the file after every save. Watching the inode of a file
# instead of its directory only catches the first save for most patterns.

set -eu

MAGENTO=/tmp/magento
MODULE=$MAGENTO/app/code/Acme/Demo
TEMPLATES=$MODULE/view/frontend/templates
LOG=/tmp/watcher.log
PATTERNS="inplace rename-over jetbrains vim recreate"
SAVES=3
FAILED=0

mkdir -p $MAGENTO/app/etc $MAGENTO/var/cache $TEMPLATES
echo '<?php return [];' > $MAGENTO/app/etc/env.php
for pattern in $PATTERNS; do echo x > $TEMPLATES/$pattern.phtml; done
cat > $MAGENTO/var/cache-clean-config.json <<JSON
{"app": {"cache_types": {"config": 1, "block_html": 1, "full_page": 1}},
 "modules": ["$MODULE"],
 "themes": []}
JSON

node bin/cache-clean.js --watch --no-flood-guard --verbose -d $MAGENTO > $LOG 2>&1 &
WATCHER=$!
trap 'kill $WATCHER 2> /dev/null || true' EXIT

for _ in $(seq 1 50); do
    grep -q "Watcher initialized" $LOG && break
    sleep 0.2
done
if ! grep -q "Watcher initialized" $LOG; then
    echo "The watcher did not start:"
    cat $LOG
    exit 1
fi

processed() {
    grep -c "Processing .*/$1.phtml\$" $LOG || true
}

echo "Watcher notices repeated saves"
for pattern in $PATTERNS; do
    missed=""
    for save in $(seq 1 $SAVES); do
        before=$(processed $pattern)
        node test/integration/watcher/save.js $pattern $TEMPLATES/$pattern.phtml
        # longer than the 500ms the watcher ignores further events for the same file
        sleep 0.8
        [ "$(processed $pattern)" -gt "$before" ] || missed="$missed $save"
    done
    if [ -z "$missed" ]; then
        echo "  ok    $pattern"
    else
        echo "  FAIL  $pattern: missed save(s)$missed of $SAVES"
        FAILED=1
    fi
done

if [ "$FAILED" -ne 0 ]; then
    echo "Watcher tests failed"
    exit 1
fi
echo "Watcher tests passed"
