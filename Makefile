# The builds run in a Docker container, so neither Clojure nor Node.js need to
# be installed on the host. The container has no network access, and can only
# write to the bin and export directories.
#
# The compiler cache in target/ lives on a tmpfs, so every build starts clean.
# A build that reuses the cache of a previous one produces different output,
# and CI checks that the committed files match a clean build.

IMAGE := magento-cache-clean-build

DOCKER_RUN := docker run --rm \
	--network none \
	--read-only \
	--tmpfs /tmp:mode=1777 \
	--env CLJ_CACHE=/tmp/cpcache \
	--user $(shell id -u):$(shell id -g) \
	--volume "$(CURDIR)":/app:ro \
	--volume "$(CURDIR)/bin":/app/bin \
	--volume "$(CURDIR)/export":/app/export \
	--tmpfs /app/target:mode=1777 \
	$(IMAGE)

FIGWHEEL := $(DOCKER_RUN) clojure -M -m figwheel.main

.PHONY: all image build lib debug-build test integration-test install-dev-dir install-debug repl

all: build lib

image:
	docker build --tag $(IMAGE) .
	@# Mount point for the tmpfs, the directory itself stays empty
	mkdir -p target

build: image
	$(FIGWHEEL) -O advanced -bo build
	chmod +x bin/cache-clean.js

lib: image
	$(FIGWHEEL) -O advanced -bo lib

debug-build: image
	$(FIGWHEEL) -O simple -bo build
	chmod +x bin/cache-clean.js

# Unit tests, compiled and run with Node.js in the build container.
test: image
	$(DOCKER_RUN) sh -c 'clojure -M:test -m figwheel.main -O simple -bo test && node target/test.js'

# Runs bin/cache-clean.js against a Redis container, see test/integration/run.sh
integration-test: image
	IMAGE=$(IMAGE) test/integration/run.sh

install-dev-dir:
	rsync -a ./ $(HOME)/.composer/vendor/mage-os/magento-cache-clean/

install-debug: debug-build install-dev-dir

repl:
	clj -Abuild-dev
