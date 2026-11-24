(ns cache.storage.varnish
  (:require [clojure.string :as string]
            [cache.storage :as storage]
            [log.log :as log]))

(defonce http (js/require "http"))

(def retry-after-ms 60000)

;; Timestamp for each server until which no PURGE requests are sent to it,
;; after a request failed
(defonce disabled-until (atom {}))

(defn- now []
  (.getTime (js/Date.)))

(defn- enabled? [server time]
  (<= (get @disabled-until server 0) time))

(defn- purge-options [server pattern]
  {:protocol "http:"
   :hostname (:host server "localhost")
   :port     (:port server "80")
   :method   "PURGE"
   :path     "/"
   :headers  {"X-Magento-Tags-Pattern" pattern}
   :timeout  10000})

(defn request [options success-callback error-callback]
  (doto (.request http options success-callback)
    (.on "error" error-callback)
    (.end)))

(defn in-body-status [body]
  (let [status (re-find #"<title>(\d+).+</title>" body)]
    (int (second status))))

(defn- handle-varnish-response
  "Successful responses might have status code 200 or 0.
  If the response code is 0, then the body should contain \"200 Purged\".
  (The latter is produced if synth() is used in the vcl to generate a synthetic response.)"
  [res]
  ;; aget keeps the property name intact in advanced builds
  (let [status (int (aget res "statusCode"))]
    (.on res "data" (fn [buffer]
                      (let [body (str buffer)
                            status (if (= 0 status) (in-body-status body) status)]
                        (when-not (= 200 status)
                          (log/notice "Varnish response code" status)
                          (log/notice "Response:" body)))))))

(defn- handle-varnish-error
  "Pause the PURGE requests to a server that can't be reached. They are
  attempted again later, since Varnish might only have been restarted."
  [server e]
  (log/debug "Varnish request error: " (.-message e))
  (when (enabled? server (now))
    (log/info "No Varnish PURGEs will be sent to" (:host server "localhost")
              "for the next" (/ retry-after-ms 1000) "seconds"))
  (swap! disabled-until assoc server (+ (now) retry-after-ms)))

(defn- purge-with-pattern!
  "Send Varnish PURGE request with the given X-Magento-Tags-Pattern header.

  Reference PHP implementation at
  \\Magento\\CacheInvalidate\\Model\\PurgeCache::sendPurgeRequest"
  [server pattern]
  (when (enabled? server (now))
    (let [options (clj->js (purge-options server pattern))]
      (log/debug "Varnish request:" options)
      (request options handle-varnish-response #(handle-varnish-error server %)))))

(defn- tags->pattern
  "Transform the given tag or tags into a regular expression pattern for use in
  the X-Magento-Tags-Pattern header.

  Reference PHP implementation at
  \\Magento\\CacheInvalidate\\Observer\\InvalidateVarnishObserver::execute"
  [tags]
  (if (string? tags)
    (tags->pattern [tags])
    (apply str (interpose "|" (map #(str "((^|,)" % "(,|$))") (distinct tags))))))

(defn purge-tags! [server tags]
  (purge-with-pattern! server (tags->pattern tags)))

(defn purge-all! [server]
  (purge-with-pattern! server ".*"))

(defrecord Varnish [servers]
  storage/CacheStorage

  (clean-tag [this tag]
    (run! #(purge-tags! % [tag]) servers))

  (clean-id [this id]
    (log/notice (str "Deleting specific cache ID is not supported "
                     "with the varnish backend")))

  (clean-all [this]
    (run! purge-all! servers))

  (close [this]))

(defn create [servers]
  (->Varnish servers))
