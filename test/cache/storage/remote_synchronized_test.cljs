(ns cache.storage.remote-synchronized-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [cache.cache :as cache]
            [cache.storage :as storage]
            [cache.storage.remote-synchronized :as remote-synchronized]))

;; Records the calls, so the tests can check what is delegated to a backend
(defrecord Recorder [id-prefix calls]
  storage/CacheStorage
  (clean-tag [this tag] (swap! calls conj [:clean-tag tag]))
  (clean-id [this id] (swap! calls conj [:clean-id id]))
  (clean-all [this] (swap! calls conj [:clean-all]))
  (close [this] (swap! calls conj [:close])))

(defn- recorder [id-prefix]
  (->Recorder id-prefix (atom [])))

(def config {:id_prefix "t_"
             :backend "\\Magento\\Framework\\Cache\\Backend\\RemoteSynchronizedCache"
             :backend_options {:remote_backend "Cm_Cache_Backend_File"
                               :remote_backend_options {:cache_dir "/tmp/remote"}
                               :local_backend "Cm_Cache_Backend_File"
                               :local_backend_options {:cache_dir "/tmp/local"}}})

(deftest extract-config-test
  (is (= {:backend "Cm_Cache_Backend_File" :id_prefix "t_" :backend_options {:cache_dir "/tmp/local"}}
         (remote-synchronized/extract-local-config config)))
  (is (= {:backend "Cm_Cache_Backend_File" :id_prefix "t_" :backend_options {:cache_dir "/tmp/remote"}}
         (remote-synchronized/extract-remote-config config))))

(deftest id-prefix-test
  (testing "the id prefix of the remote backend is used to build the tags and ids"
    (is (= "t_" (:id-prefix (remote-synchronized/create (recorder "t_") (recorder "t_"))))))
  (testing "the storage created from the cache configuration has the id prefix of the frontend"
    (is (= "t_" (:id-prefix (cache/get-storage config))))
    (is (= "t_" (:id-prefix (cache/get-storage
                              (assoc config :backend "Magento\\Framework\\Cache\\Backend\\RemoteSynchronizedCache")))))))

(deftest clean-id-test
  (let [local (recorder "t_")
        remote (recorder "t_")]
    (storage/clean-id (remote-synchronized/create local remote) "t_A")
    (testing "the version hash of the id is removed from the remote backend, too"
      (is (= #{[:clean-id "t_A"] [:clean-id "t_A:hash"]} (set @(:calls remote))))
      (is (= 2 (count @(:calls remote)))))
    (is (= [[:clean-id "t_A"]] @(:calls local)))))

(deftest delegation-test
  (let [local (recorder "t_")
        remote (recorder "t_")
        storage (remote-synchronized/create local remote)]
    (storage/clean-tag storage "t_CONFIG")
    (storage/clean-all storage)
    (storage/close storage)
    (is (= [[:clean-tag "t_CONFIG"] [:clean-all] [:close]] @(:calls remote)))
    (is (= [[:clean-tag "t_CONFIG"] [:clean-all] [:close]] @(:calls local)))))
