(ns cache.storage.redis-test
  (:require [cljs.test :refer-macros [deftest is are testing]]
            [cache.storage :as storage]
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
  (is (= {} (connect-options {:server "localhost"})))
  (is (= {:auth_pass "secret"} (connect-options {:server "localhost" :password "secret"})))
  (testing "the database is selected with a separate command"
    (is (= {:host "redis.local"} (connect-options {:server "redis.local" :database 2})))))

(deftest when-selected-test
  (let [redis {:selected (atom {:done? false :waiting []})}
        calls (atom [])]
    (#'redis/when-selected redis #(swap! calls conj 1))
    (#'redis/when-selected redis #(swap! calls conj 2))
    (is (= [] @calls) "commands wait until the database is selected")
    (#'redis/release! (:selected redis))
    (is (= [1 2] @calls) "waiting commands are sent in order")
    (#'redis/when-selected redis #(swap! calls conj 3))
    (is (= [1 2 3] @calls) "later commands are sent right away")))

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

(defn- redis-error [code message]
  (let [err (js/Error. message)]
    (set! (.-code err) code)
    err))

(deftest command-error-test
  (let [verbosity @log/verbosity
        on-failure @storage/on-failure
        failures (atom 0)]
    (log/set-verbosity! -1)
    (reset! storage/on-failure #(swap! failures inc))
    (try
      (testing "a closed connection is a failure that is reported once"
        (let [failed (atom false)]
          (#'redis/command-error failed (redis-error "NR_CLOSED" "Stream connection ended and command aborted."))
          (#'redis/command-error failed (redis-error "NR_CLOSED" "Stream connection ended and command aborted."))
          (is (true? @failed))
          (is (= 1 @failures))))
      (reset! failures 0)
      (testing "a command rejected by redis is a failure that is reported once"
        (let [failed (atom false)]
          (#'redis/command-error failed (redis-error "READONLY" "READONLY You can't write against a read only replica."))
          (#'redis/command-error failed (redis-error "READONLY" "READONLY You can't write against a read only replica."))
          (is (true? @failed))
          (is (= 1 @failures))))
      (reset! failures 0)
      (testing "the retry strategy reports the failure and gives up"
        (let [failed (atom false)
              result ((#'redis/give-up-connecting failed) #js {:error (js/Error. "connect ECONNREFUSED")})]
          (is (instance? js/Error result))
          (is (true? @failed))
          (is (= 1 @failures))))
      (testing "the retry strategy also gives up if the connection was closed without an error"
        (let [result ((#'redis/give-up-connecting (atom false)) #js {:error nil})]
          (is (= "Redis connection closed" (.-message result)))))
      (finally
        (reset! storage/on-failure on-failure)
        (log/set-verbosity! verbosity)))))

(deftest command-callback-test
  (let [redis {:n-pending (atom 0) :failed (atom false)}
        callback (#'redis/command-callback redis)]
    (is (= 1 @(:n-pending redis)) "the command is pending until the callback is called")
    (callback nil "OK")
    (is (= 0 @(:n-pending redis)))
    (is (false? @(:failed redis)))))
