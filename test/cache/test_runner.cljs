(ns cache.test-runner
  (:require [cljs.test :as test :refer-macros [run-tests]]
            [cache.core-test]
            [cache.cache-test]
            [cache.config.php-test]
            [cache.storage.redis-test]
            [cache.storage.remote-synchronized-test]
            [cache.storage.varnish-test]
            [cache.storage.file-test]
            [file.system-test]
            [magento.app-test]
            [magento.generated-code-test]
            [magento.service-contract-test]
            [php.reader-test]))

;; Exit with a non-zero code on failures, so make and CI notice them
(defmethod test/report [::test/default :end-run-tests] [summary]
  (when-not (test/successful? summary)
    (.exit js/process 1)))

(defn -main [& _]
  (run-tests 'cache.core-test
             'cache.cache-test
             'cache.config.php-test
             'cache.storage.redis-test
             'cache.storage.remote-synchronized-test
             'cache.storage.varnish-test
             'cache.storage.file-test
             'file.system-test
             'magento.app-test
             'magento.generated-code-test
             'magento.service-contract-test
             'php.reader-test))

(set! *main-cli-fn* -main)
