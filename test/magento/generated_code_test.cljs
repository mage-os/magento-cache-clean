(ns magento.generated-code-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [magento.generated-code :as generated]))

(deftest maybe-generated-classes-test
  (testing "class"
    (let [classes (set (generated/maybe-generated-classes "\\Acme\\Demo\\Model\\Thing"))]
      (is (contains? classes "\\Acme\\Demo\\Model\\Thing\\Interceptor"))
      (is (contains? classes "\\Acme\\Demo\\Model\\Thing\\Proxy"))
      (is (not (contains? classes "\\Acme\\Demo\\Model\\ThingFactory")))
      (is (not (contains? classes "\\Acme\\Demo\\Model\\ThingExtension")))))
  (testing "interface"
    (let [classes (set (generated/maybe-generated-classes "\\Acme\\Demo\\Api\\Data\\ThingInterface"))]
      (is (contains? classes "\\Acme\\Demo\\Api\\Data\\ThingExtension"))
      (is (contains? classes "\\Acme\\Demo\\Api\\Data\\ThingExtensionInterface"))
      (is (contains? classes "\\Acme\\Demo\\Api\\Data\\Thing\\Repository")))))
