(ns cache.config.php-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as string]
            [cache.config.php :as php]))

(deftest php-code-test
  (testing "the code must not contain double quotes, see win-command"
    (is (not (string/includes? @#'php/read-file-php "\"")))
    (is (not (string/includes? @#'php/list-components-php "\""))))
  (testing "paths are passed as arguments, they are not part of the code"
    (is (string/includes? @#'php/read-file-php "$argv[1]"))
    (is (string/includes? @#'php/list-components-php "getPaths($argv[2])"))))

(deftest win-command-test
  (is (= "php -r \"echo 1;\" -- \"C:/www/my shop/app/etc/env.php\" \"module\""
         (#'php/win-command "echo 1;" ["C:/www/my shop/app/etc/env.php" "module"]))))
