(ns cache.storage.redis-test
  (:require [cljs.test :refer-macros [deftest is are testing]]
            [cache.storage.redis :as redis]
            [log.log :as log]))

(defn- server-options [backend-options]
  (#'redis/server-options {:backend_options backend-options}))

(defn- connect-options [backend-options]
  (#'redis/connect-options {:backend_options backend-options}))

(deftest server-options-test
  (testing "defaults are left to the redis client"
    (is (= {} (server-options {:server "localhost" :port "6379"})))
    (is (= {} (server-options {:server "127.0.0.1"})))
    (is (= {} (server-options {}))))
  (testing "tcp server"
    (is (= {:host "redis.local"} (server-options {:server "redis.local"})))
    (is (= {:host "redis.local"} (server-options {:server "tcp://redis.local"})))
    (is (= {:host "redis.local" :port "6380"} (server-options {:server "redis.local" :port "6380"})))
    (is (= {:port "6380"} (server-options {:server "localhost" :port "6380"}))))
  (testing "unix socket"
    (is (= {:path "/var/run/redis.sock"} (server-options {:server "/var/run/redis.sock"})))
    (is (= {:path "/var/run/redis.sock"} (server-options {:server "unix:///var/run/redis.sock" :port "6380"})))))

(deftest connect-options-test
  (is (= {:db 0} (connect-options {:server "localhost"})))
  (is (= {:host "redis.local" :db 2} (connect-options {:server "redis.local" :database 2})))
  (is (= {:db 0 :auth_pass "secret"} (connect-options {:server "localhost" :password "secret"}))))

(deftest prefix-keys-test
  (is (= ["zc:k:t_A" "zc:k:t_B"] (#'redis/prefix-keys ["t_A" "t_B"])))
  (is (= [] (#'redis/prefix-keys []))))

(deftest symfony-keys-test
  (testing "data key and reverse index key for each id"
    (is (= ["t_:A" "cache:id_tags:t_A"] (#'redis/symfony-keys "t_" ["A"])))
    (is (= ["t_:A" "cache:id_tags:t_A" "t_:B" "cache:id_tags:t_B"] (#'redis/symfony-keys "t_" ["A" "B"])))
    (is (= [] (#'redis/symfony-keys "t_" [])))))

(deftest strip-id-prefix-test
  (are [prefix id expected] (= expected (#'redis/strip-id-prefix prefix id))
    "t_" "t_A" "A"
    "t_" "A" "A"
    "t_" "x_t_A" "x_t_A"
    "" "t_A" "t_A"
    nil "t_A" "t_A"))

(deftest pending-task-test
  (let [verbosity @log/verbosity]
    ;; the task logs the error thrown by the delete fn
    (log/set-verbosity! -1)
    (try
      (testing "passes the ids on and marks the task as done"
        (let [n-pending (atom 1)
              seen (atom nil)]
          ((#'redis/pending-task n-pending #(reset! seen %)) ["A"])
          (is (= ["A"] @seen))
          (is (= 0 @n-pending))))
      (testing "marks the task as done without calling the delete fn if the lookup failed"
        (let [n-pending (atom 1)
              called? (atom false)]
          ((#'redis/pending-task n-pending #(reset! called? true)) nil)
          (is (false? @called?))
          (is (= 0 @n-pending))))
      (testing "marks the task as done if the delete fn throws"
        (let [n-pending (atom 2)]
          ((#'redis/pending-task n-pending #(throw (ex-info "boom" {:ids %}))) ["A"])
          (is (= 1 @n-pending))))
      (finally
        (log/set-verbosity! verbosity)))))
