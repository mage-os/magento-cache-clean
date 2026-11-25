(ns cache.storage)

(defprotocol CacheStorage
  (clean-tag [storage tag] "Deletes all cache records associated with the given tag.")
  (clean-id [storage id] "Delete the cache record matching the given ID")
  (clean-all [storage] "Delete all cache records and tags.")
  (close [storage] "Close connection to storage, called before shutdown."))


;; Called when a storage could not be cleaned. The storages report the error
;; themselves, this is for the command line tool to set its exit code.
;; It is not set when the watcher is used as a library.
(defonce on-failure (atom nil))

(defn failed! []
  (when-let [f @on-failure]
    (f)))
