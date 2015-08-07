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
