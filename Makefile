# The builds run in a Docker container, so neither Clojure nor Node.js need to
# be installed on the host. The container has no network access, and can only
# write to the bin, export and target directories.

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
	--volume "$(CURDIR)/target":/app/target \
	$(IMAGE)

FIGWHEEL := $(DOCKER_RUN) clojure -M -m figwheel.main

.PHONY: all image build lib debug-build install-dev-dir install-debug repl

all: build lib

image:
	docker build --tag $(IMAGE) .
	mkdir -p target

build: image
	$(FIGWHEEL) -O advanced -bo build
	chmod +x bin/cache-clean.js

lib: image
	$(FIGWHEEL) -O advanced -bo lib

debug-build: image
	$(FIGWHEEL) -O simple -bo build
	chmod +x bin/cache-clean.js

install-dev-dir:
	rsync -a ./ $(HOME)/.composer/vendor/mage-os/magento-cache-clean/

install-debug: debug-build install-dev-dir

repl:
	clj -Abuild-dev
