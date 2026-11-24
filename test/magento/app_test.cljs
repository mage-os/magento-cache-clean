(ns magento.app-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [magento.app :as app]))

(deftest varnish-hosts-config-test
  (testing "port given as part of the host"
    (is (= [{:host "varnish" :port "6081"}]
           (app/varnish-hosts-config {:http_cache_hosts [{:host "varnish:6081"}]}))))
  (testing "port given separately"
    (is (= [{:host "varnish" :port "6081"}]
           (app/varnish-hosts-config {:http_cache_hosts [{:host "varnish" :port "6081"}]}))))
  (testing "no varnish hosts configured"
    (is (empty? (app/varnish-hosts-config {})))))

(deftest empty-id-prefix-test
  (is (app/empty-id-prefix? {}))
  (is (app/empty-id-prefix? {:id_prefix ""}))
  (is (not (app/empty-id-prefix? {:id_prefix "t_"}))))

(deftest file-cache-backend-test
  (is (app/file-cache-backend? {}))
  (is (app/file-cache-backend? {:backend "Cm_Cache_Backend_File"}))
  (is (not (app/file-cache-backend? {:backend "Cm_Cache_Backend_Redis"}))))

(deftest add-default-config-values-test
  (testing "the default cache dir is added for an explicit file backend"
    (is (= {:backend "Cm_Cache_Backend_File"
            :id_prefix "t_"
            :backend_options {:cache_dir "/var/www/var/cache"}}
           (app/add-default-config-values "/var/www/" {:backend "Cm_Cache_Backend_File" :id_prefix "t_"} :default))))
  (testing "the default cache dir is added if no backend is configured"
    (is (= {:backend "Cm_Cache_Backend_File"
            :id_prefix "t_"
            :backend_options {:cache_dir "/var/www/var/cache"}}
           (app/add-default-config-values "/var/www/" {:id_prefix "t_"} :default))))
  (testing "a configured cache dir is kept"
    (is (= "/dev/shm/cache"
           (-> (app/add-default-config-values "/var/www/"
                                              {:backend "Cm_Cache_Backend_File"
                                               :id_prefix "t_"
                                               :backend_options {:cache_dir "/dev/shm/cache"}}
                                              :default)
               :backend_options :cache_dir))))
  (testing "other backends get no cache dir"
    (is (= {:backend "Cm_Cache_Backend_Redis" :id_prefix "t_" :backend_options {:server "redis"}}
           (app/add-default-config-values "/var/www/"
                                          {:backend "Cm_Cache_Backend_Redis"
                                           :id_prefix "t_"
                                           :backend_options {:server "redis"}}
                                          :default)))))

(deftest default-cache-dir-test
  (is (= "/var/www/var/cache" (app/default-cache-dir "/var/www/" :default)))
  (is (= "/var/www/var/page_cache" (app/default-cache-dir "/var/www/" :page_cache))))

(deftest memoized-by-test
  (let [version (atom 1)
        calls (atom 0)
        f (app/memoized-by #(deref version) (fn [x] (swap! calls inc) (* x @version)))]
    (is (= 2 (f 2)))
    (is (= 2 (f 2)))
    (is (= 1 @calls) "the result is reused while by-fn returns the same value")
    (reset! version 3)
    (is (= 6 (f 2)))
    (is (= 2 @calls) "the result is recalculated when by-fn returns a different value")))
