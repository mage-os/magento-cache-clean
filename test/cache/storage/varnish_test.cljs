(ns cache.storage.varnish-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [cache.storage.varnish :as varnish]))

(deftest tags->pattern-test
  (is (= "((^|,)FPC(,|$))" (#'varnish/tags->pattern "FPC")))
  (is (= "((^|,)FPC(,|$))" (#'varnish/tags->pattern ["FPC"])))
  (is (= "((^|,)cat_p_1(,|$))|((^|,)cat_c_2(,|$))" (#'varnish/tags->pattern ["cat_p_1" "cat_c_2"])))
  (testing "duplicate tags are only added once"
    (is (= "((^|,)FPC(,|$))" (#'varnish/tags->pattern ["FPC" "FPC"])))))

(deftest purge-options-test
  (is (= {:protocol "http:" :hostname "varnish" :port "6081" :method "PURGE" :path "/"
          :headers {"X-Magento-Tags-Pattern" ".*"} :timeout 10000}
         (#'varnish/purge-options {:host "varnish" :port "6081"} ".*")))
  (testing "host and port defaults"
    (let [options (#'varnish/purge-options {} ".*")]
      (is (= "localhost" (:hostname options)))
      (is (= "80" (:port options))))))

(deftest in-body-status-test
  (is (= 200 (varnish/in-body-status "<html><head><title>200 Purged</title></head></html>")))
  (is (= 405 (varnish/in-body-status "<title>405 Method not allowed</title>")))
  (is (= 0 (varnish/in-body-status "no title"))))
