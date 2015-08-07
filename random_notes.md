These are personal notes to myself that don't belong on the issue tracker yet so I don't forget ideas later

# Plugins
Pugins (component-query/state-inspecting tools) can transact into the database with namespaced attributes that are local, and then use that when rendering/wrapping other components to know what to do. State is managed in the same way as normal components. e.g. user holds down alt key, {:inspector/enabled? true} is transacted locally into the db, this causes the instrumented components to shine green with an overlay, clicking on the overlay inspected the query/input/local state of the component. User stops holding the alt key, [db/remove ... :inspector/enabled? true] (or equivalent) and overlays go away.

# Seamless SS calls w/ non-transit-able data
If a ss function is mean to be called with files, ArrayBuffers, blobs, etc., the call can happen as normal, but upon noticing that the type is non-transit-serializeable data, Dato should instead create store a local (temp) ref in an atom somewhere, tell the server that it intends to call a fn with this data (and its type), arrange to first upload the data to the server, and then call the function as a normal transit-able ss function. Maybe the server gives the client a token to use to upload the resource via a separate channel - the token would operate as both authorization of the upload and identification of the resource. Something similar might have to happen to get the data back to the client, but that seems more rare.

# Remote Query Syntax
;; Goal
;; 1. Get list of all chat messages for rooms X,Y,Z
;; 2. Sort by created-at
;; 3. Get last N messages
;; 4. Be notified if query is invalidated with new data
;; Clientside
;; 1. Request query results
;; 2. Be notified by the server of updates that would affect this query
;;    (without necessarily knowing which query caused the update)

;; cpull: runs immediately against the local datascript, returns the
;; value. It simultaneously (async) sends the query to the server,
;; registers a live pull, and updates the local datascript with the
;; initial server results, and and subsequent updates, until the query
;; has been unregistered.

(dato/cpull (keyword (str "find-" (:name channel)))
            ;; Be explicit this is a mini declarative DSL, not full Clojure
            {:q       [* {(limit (sort :msg/_channel :msg/timestamp) 1000) [* {:msg/user [:db/id]}]}]
             :lookup  [:channel/name "Lobby"]
             ;; :where [?eid :channel/name "Lobby"]})

(dato/cpull-many :find-threads
            ;; Be explicit this is a mini declarative DSL, not full Clojure
            {:q      [:thread/title :thread/timestamp :thread/body
                      {:thread/user [:user/username]}
                      {:thread/children ...}]
             ;; Either :lookups XOR :where
             ;; :lookups [eid1 eid2 [lookup ref]]
             ;;
             :where [[?eid :dato/type :thread]
                     [?eid :thread/title "blah"]]})

(dato/rpull (keyword "find-user-threads")
            {:q      [:thread/title :thread/timestamp :thread/body
                      {:thread/user [:user/username]}
                      {:thread/children ...}]
             :lookup 999})
;;;; Server-side
(register-query! ...
   (run-query-and-notify-client))

;; monitor for updates   
(handle-tx
 (doseq [[client query] listening-clients]
   (notify-client! client (:name query) (d/pull (:pull query)))))

