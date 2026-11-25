(ns cache.cache-test
  (:require [cljs.test :refer-macros [deftest is are testing]]
            [cache.cache :as cache]
            [cache.storage.file :as file]))

(deftest cachetype->tag-test
  (are [type tag] (= tag (#'cache/cachetype->tag type))
    "config" "CONFIG"
    "block_html" "BLOCK_HTML"
    "full_page" "FPC"
    "layout" "LAYOUT_GENERAL_CACHE_TAG"
    "collections" "COLLECTION_DATA"
    "hyva_svg" "HYVA_ICONS"))

(deftest magefile->cachetypes-test
  (are [file types] (= types (cache/magefile->cachetypes file))
    "/app/code/Acme/Demo/etc/di.xml" ["config"]
    "/app/code/Acme/Demo/etc/frontend/di.xml" ["config"]
    "/app/code/Acme/Demo/etc/config.xml" ["config"]
    "/app/code/Acme/Demo/etc/adminhtml/menu.xml" ["config" "block_html"]
    "/app/code/Acme/Demo/i18n/de_DE.csv" ["translate" "full_page"]
    "/app/code/Acme/Demo/view/frontend/layout/default.xml" ["layout" "full_page"]
    "/app/code/Acme/Demo/view/base/page_layout/empty.xml" ["layout" "full_page"]
    "/app/code/Acme/Demo/view/frontend/templates/list.phtml" ["block_html" "full_page"]
    "/app/code/Acme/Demo/Block/Listing.php" ["block_html" "full_page"]
    "/app/design/frontend/Acme/default/theme.xml" ["block_html" "full_page"]
    "/app/code/Acme/Demo/etc/frontend/sections.xml" ["full_page"]
    "/app/code/Acme/Demo/view/frontend/requirejs-config.js" ["full_page"]
    "/app/design/frontend/Acme/default/web/svg/cart.svg" ["hyva_svg"])
  (testing "files without a matching cache type"
    (is (= [] (cache/magefile->cachetypes "/app/code/Acme/Demo/Model/Thing.php")))
    (is (= [] (cache/magefile->cachetypes "/app/code/Acme/Demo/etc/crontab.xml")))))

(deftest magefile->cacheids-test
  (are [file ids] (= ids (cache/magefile->cacheids file))
    "/app/code/Acme/Demo/etc/crontab.xml" ["crontab_config_cache"]
    "/app/code/Acme/Demo/etc/adminhtml/system.xml" ["adminhtml__backend_system_configuration_structure"]
    "/app/code/Acme/Demo/etc/frontend/routes.xml" ["frontend::RoutesConfig"]
    "/app/code/Acme/Demo/etc/frontend/events.xml" ["frontend__event_config_cache"]
    "/app/code/Acme/Demo/etc/acl.xml" ["provider_acl_resources_cache"]
    "/app/code/Acme/Demo/view/adminhtml/ui_component/demo_listing.xml" ["ui_component_configuration_data_demo_listing"]
    "/app/code/Acme/Demo/view/adminhtml/pagebuilder/content_type/banner.xml" ["pagebuilder_config" "content_type_banner"])
  (is (nil? (cache/magefile->cacheids "/app/code/Acme/Demo/Model/Thing.php"))))

(deftest get-storage-file-backend-test
  (testing "explicit file backend"
    (let [storage (cache/get-storage {:backend "Cm_Cache_Backend_File"
                                      :id_prefix "t_"
                                      :backend_options {:cache_dir "/tmp/cache"}})]
      (is (instance? file/File storage))
      (is (= "/tmp/cache/" (:cache-dir storage)))
      (is (= "t_" (:id-prefix storage)))
      (is (= "mage" (:file-name-prefix storage)))))
  (testing "unknown backend without a server falls back to the file backend"
    (is (instance? file/File (cache/get-storage {:backend "Some\\Other\\Backend"
                                                 :backend_options {:cache_dir "/tmp/cache"}}))))
  (testing "file backend without a cache dir"
    (is (thrown? ExceptionInfo (cache/get-storage {:backend "Cm_Cache_Backend_File"})))))
