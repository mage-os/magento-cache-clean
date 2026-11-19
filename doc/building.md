## Building

The tool is written in ClojureScript.
The build runs in a Docker container, so the only requirements on the host are Docker and `make`.
Neither Clojure nor Node.js need to be installed.

```
$ make
```

The first run builds the `magento-cache-clean-build` image from the `Dockerfile`, which downloads the Clojure dependencies.
The builds themselves then run without network access, and the container can only write to the `bin` and `export` directories.
The npm dependencies are not installed during the build, they are taken from the `node_modules` directory in the repository.
Only the packages needed at runtime are in there: `redis` and its three dependencies.

This will compile the `bin/cache-clean.js` script (intended to be run as a node script) and the `export/watcher.js` file (intended to be consumed from node in custom dev task runner).

Check the `Makefile` for more details.


### Reproducible builds

The compiled files are committed to the repository, because Composer installs them as they are.
The compiler cache is kept on a tmpfs inside the container, so every build starts clean and produces the same output for the same source.
The `Build and test` GitHub workflow rebuilds both files and fails if they differ from the committed ones.
So always commit the output of `make` together with the source changes.

### Tests

```
$ make test
$ make integration-test
```

`make test` compiles the unit tests in the `test` directory and runs them with Node.js in the build container.

`make integration-test` runs the compiled `bin/cache-clean.js` against a Redis container and checks that the expected keys of both the legacy and the Symfony key layout are removed.
The keys are seeded by `test/integration/run.sh`, they are not written by a real Magento instance.
