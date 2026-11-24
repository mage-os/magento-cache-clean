(ns cache.storage.redis
  (:require [cache.storage :as storage]
            [clojure.string :as string]
            [log.log :as log]
            [cljs.core.async :refer [go-loop timeout <!]]))

(defonce redis (js/require "redis"))

(comment (defonce ^:const set-ids "zc:ids")
         (defonce ^:const set-tags "zc:tags")
         (defonce ^:const prefix-key "zc:k:")
         (defonce ^:const prefix-tag-id "zc:ti:"))

(def set-ids "zc:ids")
(def set-tags "zc:tags")
(def prefix-key "zc:k:")
(def prefix-tag-id "zc:ti:")

;; Key layout of the Symfony RedisTagAdapter used by Mage-OS 4 for the 'redis' backend
(def symfony-prefix-tag-id "cache:tags:")
(def symfony-prefix-id-tags "cache:id_tags:")

;; Give up if redis is not ready for commands after this time
(def ready-timeout-ms 10000)

(defn- localhost? [host]
  (#{"localhost" "127.0.0.1"} host))

(defn- default-port? [port]
  (= "6379" port))

(defn- connect-db [config]
  (or (-> config :backend_options :database) 0))

(defn- unix-socket [server]
  (and server (second (re-find #"^(?:unix://)?(/.+)" server))))

(defn- tcp-server [server]
  (and server (second (re-find #"^(?:tcp://)?([^/]+)" server))))

(defn- server-options [config]
  (let [{:keys [server port]} (:backend_options config)
        unix-socket (unix-socket server)
        tcp-server (tcp-server server)]
    (if unix-socket {:path unix-socket}
        (cond-> {}
          (and tcp-server (not (localhost? tcp-server))) (assoc :host tcp-server)
          (and port (not (default-port? port))) (assoc :port port)))))

(defn- connect-options
  "The database is not passed to the client: it selects the database without
  checking the result, and would send the commands to database 0 if the
  configured database does not exist. See select-db!"
  [config]
  (let [password (-> config :backend_options :password)]
    (cond-> (server-options config)
      password (assoc :auth_pass password))))

(defn- error-message [^js err]
  (or (.-message err) (str err)))

(defn- connection-closed?
  "True for the error of a command that was aborted because the connection is gone."
  [^js err]
  (= "NR_CLOSED" (.-code err)))

(defn- fail!
  "Report that the cache could not be cleaned.
  Only the first failure of a client is logged, since every outstanding
  command usually fails for the same reason. Returns true if it was logged."
  [failed message]
  (if (compare-and-set! failed false true)
    (do (log/error message)
        (storage/failed!)
        true)
    false))

(defn- command-error
  "A command that fails means the cache might not be clean, whether the
  connection is gone or redis rejects the command, for example on a read
  only replica. Further errors of the same client are only logged with --debug."
  [failed err]
  (when-not (fail! failed (if (connection-closed? err)
                            "Redis connection closed before all commands were completed"
                            (error-message err)))
    (log/debug (error-message err))))

(defn- give-up-connecting
  "Returns a retry strategy for the redis client that does not retry.
  The client then calls back all outstanding commands with an error.
  Without a retry strategy it retries for an hour while the commands wait
  in a queue."
  [failed]
  (fn [^js params]
    (let [^js error (aget params "error")
          reason (if error (error-message error) "Redis connection closed")]
      (fail! failed reason)
      (js/Error. reason))))

(defn- command-callback
  "Returns the callback for a command. Every command is counted as pending
  until its callback is called, so close can wait for all of them."
  [{:keys [n-pending failed]}]
  (swap! n-pending inc)
  (fn [err _]
    (try
      (when err (command-error failed err))
      (finally (swap! n-pending dec)))))

(defn- when-selected
  "Call f once the database is selected. If selecting it fails, f is called
  after the connection was ended, so the commands it sends are called back
  with an error instead of being sent."
  [{:keys [selected]} f]
  (if (:done? @selected)
    (f)
    (swap! selected update :waiting conj f)))

(defn- release!
  "Call the functions waiting for the database to be selected."
  [selected]
  (let [waiting (:waiting @selected)]
    (reset! selected {:done? true :waiting []})
    (run! #(%) waiting)))

(defn- send!
  "Send a command to redis.
  js-invoke keeps the method names intact in advanced builds."
  [redis command & args]
  (let [callback (command-callback redis)]
    (when-selected redis #(apply js-invoke (:client redis) command (concat args [callback])))))

(defn- send-batch!
  "Send several commands in one round trip. The errors of the individual
  commands are part of the result."
  [redis commands]
  (let [callback (command-callback redis)]
    (when-selected
      redis
      #(-> (js-invoke (:client redis) "batch" (clj->js commands))
           (js-invoke "exec" (fn [err results]
                               (run! (fn [e] (command-error (:failed redis) e))
                                     (filter (fn [r] (instance? js/Error r)) (array-seq (or results #js []))))
                               (callback err nil)))))))

(defn- set-members
  "Call callback with the members of the set at key.
  The callback is always called, with nil if the lookup fails,
  so callers can rely on it to track pending tasks."
  [redis key callback]
  (when-selected
    redis
    #(js-invoke (:client redis) "smembers" key
                (fn [err ids]
                  (when err (command-error (:failed redis) err))
                  (callback (when-not err (js->clj ids)))))))

(defn- prefix-keys [ids]
  (doall (map #(str prefix-key %) ids)))

(defn- delete-cache-ids [redis ids]
  (apply log/debug "Cleaning id(s):" ids)
  (apply send! redis "del" (prefix-keys ids))
  (apply send! redis "srem" set-ids ids))

(defn- delete-tag-and-ids [redis tag ids]
  ;; TODO: make multi command
  (when (seq ids)
    (log/debug "About to clean" (count ids) "id(s)")
    (run! #(delete-cache-ids redis %) (partition-all 500 ids)))
  (send! redis "del" (str prefix-tag-id tag))
  (send! redis "srem" set-tags tag))

(defn- symfony-keys
  "Data and reverse index keys of ids without the id prefix.
  Symfony separates the namespace (the id prefix) from the id with a colon."
  [id-prefix ids]
  (doall (mapcat (fn [id] [(str id-prefix ":" id)
                           (str symfony-prefix-id-tags id-prefix id)])
                 ids)))

(defn- symfony-delete-cache-ids [redis ids]
  (apply log/debug "Cleaning id(s):" ids)
  (apply send! redis "del" (symfony-keys (:id-prefix redis) ids)))

(defn- symfony-delete-tag-and-ids [redis tag ids]
  (when (seq ids)
    (log/debug "About to clean" (count ids) "id(s)")
    (run! #(symfony-delete-cache-ids redis %) (partition-all 500 ids)))
  (send! redis "del" (str symfony-prefix-tag-id tag)))

(defn- strip-id-prefix [id-prefix id]
  (if (string/starts-with? id (str id-prefix))
    (subs id (count id-prefix))
    id))

(defn- pending-task
  "Callback for set-members that passes the ids to delete-fn, unless the
  lookup failed. The pending task is always marked as done, also when
  delete-fn throws, otherwise close would wait forever."
  [n-pending delete-fn]
  (fn [ids]
    (try
      (when ids (delete-fn ids))
      (catch :default e (log/error e))
      (finally (swap! n-pending dec)))))

(defrecord Redis [^js/RedisClient client database id-prefix n-pending failed selected]
  storage/CacheStorage

  (clean-tag [this tag]
    (swap! n-pending + 2)
    (set-members this (str prefix-tag-id tag)
                 (pending-task n-pending #(delete-tag-and-ids this tag %)))
    (set-members this (str symfony-prefix-tag-id tag)
                 (pending-task n-pending #(symfony-delete-tag-and-ids this tag %))))

  (clean-id [this id]
    (log/debug "Cleaning id:" id)
    ;; One round trip for the keys of both the legacy and the Symfony layout
    (send-batch! this [(concat ["del"]
                               (prefix-keys [id])
                               (symfony-keys id-prefix [(strip-id-prefix id-prefix id)]))
                       ["srem" set-ids id]]))

  (clean-all [this]
    (log/debug "Flushing redis db" database)
    (send! this "flushdb"))

  (close [this]
    ;; Every command is pending until redis replied or the connection failed.
    ;; Quitting earlier would hide a connection error, because the client
    ;; does not report errors any more once it is asked to quit.
    (go-loop [n @n-pending]
        (if (zero? n)
          (do (log/debug "Disconnecting redis client")
              (.quit client (fn[])))
          (do (<! (timeout 15))
              (recur @n-pending))))))

(defn- abort!
  "End the connection after a failure. The commands that are still waiting
  are called back with an error.

  The commands that were not sent yet are aborted right away. The connection
  itself is ended after the current event: when called from an error event
  the client might have to process further replies it already received.
  It fails if the connection is ended in between, and one of the replies
  might make it send the waiting commands after all."
  [^js/RedisClient client watchdog]
  (js/clearTimeout @watchdog)
  ;; Scheduled first, so it runs before anything the aborted commands schedule
  (js/setImmediate #(js-invoke client "end" true))
  (js-invoke client "flush_and_error"
             #js {:message "Command aborted because of a previous error." :code "NR_CLOSED"}
             #js {:queues #js ["offline_queue"]}))

(defn- handle-client-error
  "Handler for the error event of the client, for example for a failed
  authentication. The client keeps such a connection open while the commands
  wait in a queue. Node.js ends the process on an error event nobody listens to."
  [client failed watchdog]
  (fn [err]
    (if (connection-closed? err)
      (log/debug (error-message err))
      (do (fail! failed (error-message err))
          (abort! client watchdog)))))

(defn- handle-not-ready
  "The client waits without a limit for a server that accepts the connection
  but does not answer, and for hours for one that does not accept it."
  [client failed watchdog]
  (fn []
    (fail! failed (str "Redis is not ready after " (/ ready-timeout-ms 1000) " seconds"))
    (abort! client watchdog)))

(defn- select-db!
  "Select the database, and only then let the other commands be sent.
  Otherwise they are sent together with the select command, and are run in
  database 0 if the configured database does not exist."
  [^js/RedisClient client database selected failed watchdog]
  (js-invoke client "select" database
             (fn [err _]
               (if err
                 (do (command-error failed err)
                     (when-not (connection-closed? err)
                       (abort! client watchdog))
                     ;; After the client ended the connection
                     (js/setImmediate #(release! selected)))
                 (release! selected)))))

(defn create [config]
  (let [failed (atom false)
        watchdog (atom nil)
        selected (atom {:done? false :waiting []})
        database (connect-db config)
        options (assoc (connect-options config) :retry_strategy (give-up-connecting failed))
        id-prefix (:id_prefix config)
        ^js/RedisClient client (.createClient redis (clj->js options))
        n-pending-tasks (atom 0)]
    (reset! watchdog (js/setTimeout (handle-not-ready client failed watchdog) ready-timeout-ms))
    (.on client "ready" #(js/clearTimeout @watchdog))
    (.on client "end" #(js/clearTimeout @watchdog))
    (.on client "error" (handle-client-error client failed watchdog))
    (select-db! client database selected failed watchdog)
    (->Redis client database id-prefix n-pending-tasks failed selected)))


#_(def client (:client (create {:backend "Cm_Cache_Backend_Redis", :backend_options {:server "localhost", :database 0, :port 6379}})))
#_(def c (create {:backend "Cm_Cache_Backend_Redis", :backend_options {:server "localhost", :database 0, :port 6379}}))
