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

(deftest pause-after-error-test
  (let [disabled-until @varnish/disabled-until
        down {:host "varnish-1" :port "6081"}
        up {:host "varnish-2" :port "6081"}
        time (.getTime (js/Date.))]
    (try
      (reset! varnish/disabled-until {})
      (is (#'varnish/enabled? down time))
      (#'varnish/handle-varnish-error down (js/Error. "connect ECONNREFUSED"))
      (testing "no requests to the server right after an error"
        (is (not (#'varnish/enabled? down (.getTime (js/Date.))))))
      (testing "requests to other servers are still sent"
        (is (#'varnish/enabled? up (.getTime (js/Date.)))))
      (testing "requests are attempted again later"
        (is (#'varnish/enabled? down (+ (.getTime (js/Date.)) varnish/retry-after-ms 1))))
      (finally
        (reset! varnish/disabled-until disabled-until)))))
