(ns magento.service-contract-test
  (:require [cljs.test :refer-macros [deftest is]]
            [magento.service-contract :as service-contract]))

(deftest service-interface-test
  (is (service-contract/service-interface? "/app/code/Acme/Demo/Api/ThingRepositoryInterface.php"))
  (is (service-contract/service-interface? "/app/code/Acme/Demo/Api/Data/ThingInterface.php"))
  (is (not (service-contract/service-interface? "/app/code/Acme/Demo/Api/ThingRepository.php")))
  (is (not (service-contract/service-interface? "/app/code/Acme/Demo/Model/ThingInterface.php"))))

(deftest service-cache-ids-test
  (is (nil? (service-contract/service-cache-ids "/app/code/Acme/Demo/Model/Thing.php")))
  (is (nil? (service-contract/service-cache-ids "/does/not/exist/Api/ThingInterface.php"))))
