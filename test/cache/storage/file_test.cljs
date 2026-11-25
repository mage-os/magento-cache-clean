(ns cache.storage.file-test
  (:require [cljs.test :refer-macros [deftest is]]
            [cache.storage.file :as file]
            [util.crypto :refer [md5]]))

(deftest tag->filepath-test
  (is (= "/var/cache/mage-tags/mage---t_CONFIG"
         (#'file/tag->filepath "/var/cache/" "mage" "t_CONFIG"))))

(deftest id->filepath-test
  (let [id "t_CRONTAB_CONFIG_CACHE"
        hash-dir (subs (md5 id) 31)]
    (is (= (str "/var/cache/mage--" hash-dir "/mage---" id)
           (#'file/id->filepath "/var/cache/" "mage" id)))))

(deftest create-test
  (let [storage (file/create {:id_prefix "t_"
                              :backend_options {:cache_dir "/var/cache" :file_name_prefix "pc"}})]
    (is (= "/var/cache/" (:cache-dir storage)))
    (is (= "pc" (:file-name-prefix storage)))
    (is (= "t_" (:id-prefix storage)))))
