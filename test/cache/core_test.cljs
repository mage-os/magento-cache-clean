(ns cache.core-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [cache.core :as core]))

(deftest has-switch-test
  (is (core/has-switch? ["-w" "--watch"] ["config" "-w"]))
  (is (core/has-switch? "--version" ["--version"]))
  (is (not (core/has-switch? ["-w" "--watch"] ["config" "-v"])))
  (is (not (core/has-switch? ["-w"] []))))

(deftest find-arg-test
  (is (= "/var/www" (core/find-arg ["-d" "--directory"] ["-w" "-d" "/var/www" "config"])))
  (is (= "/var/www" (core/find-arg ["-d" "--directory"] ["--directory" "/var/www"])))
  (testing "missing flag or missing value"
    (is (nil? (core/find-arg ["-d" "--directory"] ["-w" "config"])))
    (is (nil? (core/find-arg ["-d" "--directory"] ["-w" "-d"])))))

(deftest find-log-level-test
  (is (= 1 (core/find-log-level [])))
  (is (= 2 (core/find-log-level ["-v"])))
  (is (= 2 (core/find-log-level ["config" "--verbose"])))
  (is (= 3 (core/find-log-level ["-vv"])))
  (is (= 3 (core/find-log-level ["-v" "-v"])))
  (is (= 0 (core/find-log-level ["-s"])))
  (testing "explicit verbosity wins over switches"
    (is (= "5" (core/find-log-level ["-s" "--verbosity" "5"])))))

(deftest get-file-list-test
  (is (= "files.txt" (core/get-file-list ["--file-list" "files.txt"])))
  (is (= "files.txt" (core/get-file-list ["-v" "-f" "files.txt"])))
  (is (nil? (core/get-file-list ["config"]))))

(deftest remove-switches-and-args-with-vals-test
  (is (= ["config" "layout"]
         (core/remove-switches-and-args-with-vals
           ["-w" "-d" "/var/www" "config" "-v" "--verbosity" "2" "layout" "--keep-generated"])))
  (is (= [] (core/remove-switches-and-args-with-vals ["-w" "-n" "-vv"])))
  (is (= [] (core/remove-switches-and-args-with-vals []))))

(deftest node-version-compatible-test
  (is (core/node-version-compatible? 20))
  (is (core/node-version-compatible? 22))
  (testing "the major version is parsed from a string"
    (is (core/node-version-compatible? "20"))
    (is (not (core/node-version-compatible? "18"))))
  (is (not (core/node-version-compatible? 19))))

(deftest help-and-version-switches-test
  (is (core/help-needed? ["config" "-h"]))
  (is (core/help-needed? ["--help"]))
  (is (not (core/help-needed? ["config"])))
  (is (core/display-version? ["--version"]))
  (is (not (core/display-version? ["-v"]))))
