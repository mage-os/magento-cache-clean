(ns php.reader-test
  (:require [cljs.test :refer-macros [deftest is]]
            [php.reader :as php]))

(def php-class-source
  "<?php declare(strict_types=1);

namespace Acme\\Demo\\Model;

class Thing implements ThingInterface
{
    public function getName(): string {}
    private function secret() {}
    public  function setName(string $name): void {}
}
")

(def php-interface-source
  "<?php
namespace Acme\\Demo\\Api;

interface ThingRepositoryInterface
{
    public function getById(int $id);
}
")

(deftest php-namespace-test
  (is (= "Acme\\Demo\\Model" (php/php-namespace php-class-source)))
  (is (nil? (php/php-namespace "<?php echo 1;"))))

(deftest php-class-test
  (is (= "Thing" (php/php-class php-class-source)))
  (is (nil? (php/php-class php-interface-source))))

(deftest php-interface-test
  (is (= "ThingRepositoryInterface" (php/php-interface php-interface-source)))
  (is (nil? (php/php-interface php-class-source))))

(deftest php-methods-test
  (is (= ["getName" "setName"] (php/php-methods php-class-source)))
  (is (= ["getById"] (php/php-methods php-interface-source))))

(deftest php-class->file-test
  (is (= "/Acme/Demo/Model/Thing.php" (php/php-class->file "\\Acme\\Demo\\Model\\Thing"))))
