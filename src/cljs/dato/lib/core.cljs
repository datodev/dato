(ns dato.lib.core
  (:require [cljs.core.async :as async :refer [put! chan <!]]
            [cognitect.transit :as transit]
            [datascript :as d]
            [datascript.core :as dc]
            [dato.db.utils :as dsu]
            [dato.lib.controller :as con]
            [dato.lib.websockets :as ws]
            [dato.lib.db :as db]
            [dato.lib.transit :as dato-transit]
            [dato.lib.web-peer :as web-peer]
            [om.core :as om]
            [goog.Uri]
            [talaria.api :as tal])
  (:require-macros [cljs.core.async.macros :as async :refer [alt! go go-loop]])
  (:import [goog Uri]))

(defn chan? [value]
  (satisfies? cljs.core.async.impl.protocols/ReadPort value))

(defonce network-enabled?
  (atom true))

(set! (.-networkEnabled js/window) network-enabled?)

(def transit-reader
  (transit/reader :json
                  {:handlers
                   {"r"                (fn [uri] (Uri. uri))
                    "datascript/Datom" dc/datom-from-reader}}))

(def transit-writer
  (transit/writer :json
                  {:handlers
                   {goog.Uri              (dato-transit/URIHandler.)
                    datascript.core/Datom (dato-transit/DatomHandler.)
                    datascript.btset/Iter (transit/VectorHandler.)}}))

(defn tx-report->transaction [report]
  (mapv dsu/datom->transaction (:tx-data report)))

(defn bootstrapped? [db]
  (dsu/qe-by db :dato.meta/bootrapped? true))

(defmulti ws-message-handler (fn [dato-ch msg] (:op msg)))

(defmethod ws-message-handler :default
  [dato-ch msg]
  ;; TODO replace the dato-ch handler with this

  (when (:event msg) ;; Make sure incoming event is consumable by dato
    (put! dato-ch msg)))

(defn new-dato [dato-host dato-port dato-path app-db cs-schema]
  (let [cast-bootstrapped? (atom false)
        ws-ch              (async/chan)
        dato-ch            (async/chan)
        ;; XXX: can we get rid of this?
        ss-cast!           #(put! ws-ch %)
        ;; XXX: can we get rid of this?
        tal-state          (tal/init {:port dato-port
                                      :transit-reader transit-reader
                                      :transit-writer transit-writer
                                      :message-handler (fn [msg]
                                                         (ws-message-handler dato-ch msg))})
        dato-send!         (fn [event data]
                             (tal/queue-msg! tal-state {:op event :data data}))
        controls-ch        (async/chan)
        comms              {:controls controls-ch
                            :stop     (async/chan)
                            :keyboard (async/chan)
                            :error    (async/chan)
                            :dato     dato-ch}
        cast!              (fn cast!
                             ([payload]
                              (cast! payload nil))
                             ([payload meta]
                              (if @cast-bootstrapped?
                                (put! dato-ch payload)
                                (js/console.log "Not yet bootstrapped, ignoring cast"))
                              true))
        history            (atom {:current-path nil
                                  :log          []})
        ss-data            (atom {})
        ss-api             (atom {})
        ;; ???: Do the perf implications of anonymous functions mean these
        ;; shouldn't be in a tight loop?
        fn-desc->rpc       (fn [remote-name desc]
                             (fn [& all-args]
                               ;; TODO: Check if last argument is a channel, an
                               (let [cb-or-ch (last all-args)
                                     cb       (cond
                                                (fn? cb-or-ch)   cb-or-ch
                                                (chan? cb-or-ch) #(put! cb-or-ch %)
                                                :else            false)
                                     args     (if cb
                                                (drop-last 1 all-args)
                                                all-args)]
                                 (tal/queue-msg! tal-state
                                                 {:op remote-name
                                                  :data {:args args}}
                                                 30000
                                                 (fn [reply]
                                                   (if (fn? cb)
                                                     (cb reply)
                                                     (when (:event reply)
                                                       (put! dato-ch reply)))))
                                 (when (chan? cb-or-ch)
                                   cb-or-ch))))]
    ;; Sets up datascript db so that web-peer/transact will send txes to the server
    (web-peer/web-peerify-conn app-db (fn [data]
                                        (let [ch (async/chan)]
                                          (tal/queue-msg! tal-state {:op :web-peer/transact
                                                                     :data data}
                                                          30000
                                                          (fn [reply]
                                                            (put! ch reply)))
                                          ch)))
    (def _app-db app-db)
    {:comms    comms
     ;; Just for debugging to simulate messages coming in from the
     ;; server
     :ss-cast! ss-cast!
     :conn     app-db
     :db       (fn
                 ([] @(app-db))
                 ([conn] @(conn)))
     ::ss-dato ss-data
     :ss       ss-api
     :history  history
     ;; TODO: The individual ss api functions below should be moved
     ;; into the bootstrapped :ss key. The multiple-arities and
     ;; pre-checks makes it difficult though. Need to think about how
     ;; to propagate that from server to client for a good dev
     ;; experience.
     :api      {:r-pull     (fn [data]
                              {:pre [(not (nil? (:name data)))
                                     (not (nil? (:pull data)))
                                     (not (nil? (:lookup data)))]}
                              (dato-send! :ss/r-pull data))
                :r-qes-by   (fn [data]
                              {:pre [(not (nil? (:name data)))
                                     (not (nil? (:a data)))]}
                              (dato-send! :ss/r-qes-by data))
                :r-transact (fn rtransact
                              ([datoms]
                               (rtransact datoms {}))
                              ([datoms meta]
                               {:pre [(sequential? datoms)]}
                               (dato-send! :ss/tx-requested {:datoms datoms
                                                             :meta   meta})))
                :transact!  (fn transact!
                              ([intent tx]
                               (transact! intent tx {} nil))
                              ([intent tx meta]
                               (transact! intent tx meta nil))
                              ([intent tx meta cb]
                               (cast! {:event :db/updated
                                       :data  {:intent intent
                                               ;; TODO: Decide on where the cb goes, and stick to it.
                                               :tx     (with-meta tx (merge meta {:tx/cb (or cb (:tx/cb meta))}))}})))
                :cast!      cast!
                :broadcast! (fn broadcast!
                              ([event]
                               (broadcast! event nil nil))
                              ([event payload]
                               (broadcast! event payload nil))
                              ([event payload meta]
                               (dato-send! event payload)
                               true))
                :bootstrap! (fn []
                              (js/console.log "1. Bootstrap")
                              (tal/queue-msg! tal-state {:op :ss/bootstrap}
                                              30000
                                              (fn [reply]
                                                (let [{:keys [session-id schema] :as data} (:data reply)]
                                                  (swap! ss-data merge (:ss data))
                                                  (reset! ss-api (->> (:ss data)
                                                                      (map (fn [[k v]]
                                                                             {(keyword (name k)) (fn-desc->rpc k v)}))
                                                                      (reduce merge {})))
                                                  (let [db (let [bootstrapped? (bootstrapped? @app-db)
                                                                 conn          (if bootstrapped?
                                                                                 app-db
                                                                                 (d/create-conn (merge schema db/cs-schema cs-schema)))]
                                                             (def --fconn conn)
                                                             ;; Check if we're reloading
                                                             ;; with a pre-existing db
                                                             (when-not bootstrapped?
                                                               (remove-watch app-db :global-listener)
                                                               (reset! app-db @conn)
                                                               (doto app-db
                                                                 (db/setup-listener! :global-listener))

                                                               (let [local-session-id (d/tempid :db.part/user)
                                                                     local-session-tx [{:db/id                 (d/tempid :db.part/user)
                                                                                        :dato.meta/bootrapped? true}
                                                                                       ;; TODO: This can likely be moved out to a user-land handler
                                                                                       (assoc (:session data) :db/id local-session-id)
                                                                                       {:local/current-session {:db/id local-session-id}}]]
                                                                 (d/transact! app-db (vals schema))
                                                                 (d/transact! app-db local-session-tx))

                                                               (reset! cast-bootstrapped? true))
                                                             conn)]
                                                    (put! dato-ch [:dato/bootstrap-succeeded session-id])
                                                    (js/console.log "Finished bootstrap"))))))}}))

;; TODO: Probably convert to cljs Record to reify this stuff + avoid the apply shortcut
(defn bootstrap! [dato & args]
  (apply (get-in dato [:api :bootstrap!]) args))

(defn db [dato]
  (web-peer/deref-db (:conn dato)))

(defn conn [dato]
  (:conn dato))

;; Do these need special-cased? Can just be exposed as built-in
;; server-side methods?
(defn r-qes-by [dato data]
  ((get-in dato [:api :r-qes-by]) data))

(defn r-pull [dato data]
  ((get-in dato [:api :r-pull]) data))

(defn r-transact! [dato data]
  (get-in dato [:api :r-transact]) data)

(defn transact! [dato & args]
  (apply (get-in dato [:api :transact!]) args))

(defn cast! [dato event-data]
  ((get-in dato [:api :cast!]) event-data))

(defn me [conn]
  (dsu/qe-by conn :user/me? true))

(defn local-session [conn]
  (d/entity conn (dsu/val-by conn :local/current-session)))

(defn ss [dato]
  @(:ss dato))

(defn start-loop! [dato additional-context]
  (let [dato-ch         (get-in dato [:comms :dato])
        conn            (get-in dato [:conn])
        ;; TODO: This is messy, clean up once api and ss are merged
        ;; concepts
        api             (get-in dato [:api])
        ss              (get-in dato [:ss])
        history         (get-in dato [:history])
        ;; User's additional-context isn't able to override the
        ;; dato-provided context, to prevent confusion
        context         (merge additional-context api {:dato dato
                                                       :ss   ss})
        update-history! (fn [tx-report]
                          (when (get-in tx-report [:tx-meta :tx/intent])
                            (swap! history update-in [:log] conj {:tx/intent     (get-in tx-report [:tx-meta :tx/intent])
                                                                  :time          (js/Date.)
                                                                  :tx            tx-report
                                                                  :tx/transient? (get-in tx-report [:tx-meta :tx/transient?])})))]
    ;; XXX: Uneasy with:
    ;; 1. Hard-coding history-listener here (what about other plugins?)
    ;; 2. Transactions needing history-listener-specific metadata (plugging it in now becomes difficult)
    (d/listen!
     conn
     key
     update-history!)
    (go
      (try
        (loop []
          (alt!
            dato-ch ([{:keys [sender event data] :as payload}]
                     (cond
                       ;; Special-case waiting on https://github.com/tonsky/datascript/issues/76
                       ;; Can eliminate this branch afterwards
                       (= :server/database-transacted event)
                       (let [previous-state @conn]
                         (def f-tx data)
                         ;; This is the abstraction not yet supported
                         ;; (two-phase commit, plus intermediate
                         ;; processing)
                         (web-peer/handle-transaction conn data)
                         (con/effect! context previous-state @conn payload))
                       :else
                       (let [previous-state @conn
                             tx-data        (con/transition @conn payload)
                             tx-meta        (meta tx-data)
                             full-tx-meta   (-> (if tx-meta
                                                  tx-meta
                                                  {:tx/transient? true})
                                                (update-in [:tx/intent] (fn [intent]
                                                                          (or intent event))))]
                         (if tx-data
                           (if (or (:tx/broadcast? full-tx-meta)
                                   (:tx/persist? full-tx-meta))
                             (web-peer/transact conn tx-data full-tx-meta)
                             (d/transact! conn tx-data full-tx-meta))
                           (update-history! {:tx-meta full-tx-meta}))
                         (con/effect! context previous-state @conn payload)))
                     (recur))))
        (catch js/Error e
          (js/console.error "Error in go loop" e))))))

;; Component stuff

(defn dato-node-id [owner]
  (js/Math.abs (hash (aget owner "_rootNodeID"))))

(defn get-state [owner]
  (let [db  (db (om/get-shared owner [:dato]))
        eid (dato-node-id owner)]
    (d/entity db eid)))


;; XXX: Unsure about -nr variant
(defn set-state-nr! [owner map]
  ;; No nested collections allowed (except sets, for now)
  {:pre [(not (some #(or (map? %) (vector? %)) (vals map)))]}
  (let [conn                       (conn (om/get-shared owner [:dato]))
        eid                        (dato-node-id owner)
        entity                     (merge {:db/id eid} map)
        current-state              (dsu/touch+ (d/entity @conn eid))
        ;; TODO: Might be a way to avoid this
        [update-ent remove-datoms] (reduce (fn [[update-ent remove-datoms] [k v]]
                                             (if (nil? v)
                                               (if-let [existing-value (get current-state k)]
                                                 [update-ent (conj remove-datoms [:db/retract eid k existing-value])]
                                                 [update-ent remove-datoms])
                                               [(assoc update-ent k v) remove-datoms])) [{} []] entity)
        tx-data                    (remove empty? (conj remove-datoms update-ent))]
    (d/transact conn tx-data)))

(defn set-state! [owner map]
  (set-state-nr! owner map)
  (om/refresh! owner))

(defn update-state! [owner f]
  (let [current-state (get-state owner)
        new-state     (f (dsu/touch+ current-state))]
    (set-state! owner new-state)))

(defn update-state-nr! [owner f]
  (let [current-state (get-state owner)
        new-state     (f (dsu/touch+ current-state))]
    (set-state-nr! owner new-state)))

(defn unmount! [owner]
  (let [conn (conn (om/get-shared owner [:dato]))
        eid  (dato-node-id owner)]
    ;; XXX Unclear about this, but this mimics React's default state
    ;; behavior on unmounts
    (d/transact conn [[:db.fn/retractEntity eid]])))
