## Building

The tool is written in ClojureScript.
The build runs in a Docker container, so the only requirements on the host are Docker and `make`.
Neither Clojure nor Node.js need to be installed.

```
$ make
```

The first run builds the `magento-cache-clean-build` image from the `Dockerfile`, which downloads the Clojure dependencies.
The builds themselves then run without network access, and the container can only write to the `bin`, `export` and `target` directories.
The npm dependencies are not installed during the build, they are taken from the `node_modules` directory in the repository.

This will compile the `bin/cache-clean.js` script (intended to be run as a node script) and the `export/watcher.js` file (intended to be consumed from node in custom dev task runner).

Check the `Makefile` for more details.
