# Build environment for magento-cache-clean.
#
# Provides the JDK, the Clojure CLI and Node.js, so none of them need to be
# installed on the host. The Clojure dependencies are downloaded while building
# the image; the builds themselves then run without network access, see Makefile.

# The base images are pinned by digest, so a changed tag cannot alter the build.
FROM node:22-bookworm-slim@sha256:e9f565d32859f0cbfb52125eafe8be39390f24dac302dc3cdf566e8ba3ab21ee AS node

FROM clojure:temurin-17-tools-deps-bookworm-slim@sha256:eb6e60ce666da4f2efac1b45d133649f721a529cabd8b34e95cdcd15b48d716a

# Node.js is only used by the ClojureScript compiler to index node_modules.
COPY --from=node /usr/local/bin/node /usr/local/bin/node

# The builds run as the host user, who has no home directory in the container.
ENV HOME=/home/build \
    CLJ_CONFIG=/home/build/.clojure \
    JAVA_TOOL_OPTIONS=-Duser.home=/home/build

WORKDIR /app

# Download the Clojure dependencies into the image.
COPY deps.edn ./
RUN clojure -P && chmod -R a+rX /home/build && rm -rf .cpcache
