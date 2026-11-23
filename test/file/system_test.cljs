(ns file.system-test
  (:require [cljs.test :refer-macros [deftest is async]]
            [file.system :as fs]
            [log.log :as log]))

(def node-fs (js/require "fs"))
(def os (js/require "os"))

(deftest watch-callback-error-test
  ;; An error thrown in a watch callback ends the process unless it is caught
  (async done
    (let [dir (.mkdtempSync node-fs (str (.tmpdir os) "/cache-clean-test-"))
          verbosity @log/verbosity
          changed (atom [])]
      (log/set-verbosity! -1)
      (fs/watch dir (fn [file]
                      (swap! changed conj file)
                      (throw (ex-info "boom" {}))))
      (.writeFileSync node-fs (str dir "/a.txt") "a")
      (js/setTimeout
        (fn []
          (.writeFileSync node-fs (str dir "/b.txt") "b")
          (js/setTimeout
            (fn []
              (fs/stop-watching (fs/realpath dir))
              (fs/rmdir-recursive dir)
              (log/set-verbosity! verbosity)
              (is (some #{(str (fs/realpath dir) "/a.txt")} @changed))
              (is (some #{(str (fs/realpath dir) "/b.txt")} @changed)
                  "the watch still reports changes after the callback threw")
              (done))
            1000))
        1000))))
