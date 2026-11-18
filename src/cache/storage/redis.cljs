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

(defn- connect-options [config]
  (let [password (-> config :backend_options :password)
        database (connect-db config)]
    (cond-> (server-options config)
      password (assoc :auth_pass password)
      database (assoc :db database))))

(defn- set-members
  "Call callback with the members of the set at key.
  The callback is always called, with nil if the lookup fails,
  so callers can rely on it to track pending tasks."
  [^js/RedisClient client key callback]
  (.smembers client key
             (fn [err ids]
               (when err (log/error err))
               (callback (when-not err (js->clj ids))))))

(defn- prefix-keys [ids]
  (doall (map #(str prefix-key %) ids)))

(defn- delete-cache-ids [^js/RedisClient client ids]
  (apply log/debug "Cleaning id(s):" ids)
  (apply js-invoke client "del" (prefix-keys ids))
  (apply js-invoke client "srem" set-ids ids))

(defn- delete-tag-and-ids [^js/RedisClient client tag ids]
  ;; TODO: make multi command
  (when (seq ids)
    (log/debug "About to clean" (count ids) "id(s)")
    (run! #(delete-cache-ids client %) (partition-all 500 ids)))
  (.del client (str prefix-tag-id tag))
  (.srem client set-tags tag))

(defn- symfony-keys
  "Data and reverse index keys of ids without the id prefix.
  Symfony separates the namespace (the id prefix) from the id with a colon."
  [id-prefix ids]
  (doall (mapcat (fn [id] [(str id-prefix ":" id)
                           (str symfony-prefix-id-tags id-prefix id)])
                 ids)))

(defn- symfony-delete-cache-ids [^js/RedisClient client id-prefix ids]
  (apply log/debug "Cleaning id(s):" ids)
  (apply js-invoke client "del" (symfony-keys id-prefix ids)))

(defn- symfony-delete-tag-and-ids [^js/RedisClient client id-prefix tag ids]
  (when (seq ids)
    (log/debug "About to clean" (count ids) "id(s)")
    (run! #(symfony-delete-cache-ids client id-prefix %) (partition-all 500 ids)))
  (.del client (str symfony-prefix-tag-id tag)))

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

(defrecord Redis [^js/RedisClient client database id-prefix n-pending]
  storage/CacheStorage

  (clean-tag [this tag]
    (swap! n-pending + 2)
    (set-members client (str prefix-tag-id tag)
                 (pending-task n-pending #(delete-tag-and-ids client tag %)))
    (set-members client (str symfony-prefix-tag-id tag)
                 (pending-task n-pending #(symfony-delete-tag-and-ids client id-prefix tag %))))

  (clean-id [this id]
    (delete-cache-ids client [id])
    (symfony-delete-cache-ids client id-prefix [(strip-id-prefix id-prefix id)]))

  (clean-all [this]
    (log/debug "Flushing redis db" database)
    (.flushdb client))

  (close [this]
    (go-loop [n @n-pending]
        (if (zero? n)
          (do (log/debug "Disconnecting redis client")
              (.quit client (fn[])))
          (do (<! (timeout 15))
              (recur @n-pending))))))

(defn create [config]
  (let [options (connect-options config)
        id-prefix (:id_prefix config)
        client (.createClient redis (clj->js options))
        n-pending-tasks (atom 0)]
    (->Redis client (connect-db config) id-prefix n-pending-tasks)))


#_(def client (:client (create {:backend "Cm_Cache_Backend_Redis", :backend_options {:server "localhost", :database 0, :port 6379}})))
#_(def c (create {:backend "Cm_Cache_Backend_Redis", :backend_options {:server "localhost", :database 0, :port 6379}}))
