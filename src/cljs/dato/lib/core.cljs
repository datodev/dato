(ns dato.lib.core
  (:require [cljs.core.async :as async :refer [put! chan <!]]
            [cognitect.transit :as transit]
            [datascript :as d]
            [datascript.core :as dc]
            [dato.datascript-utils :as dsu]
            [dato.lib.controller :as con]
            [dato.lib.websockets :as ws]
            [dato.lib.db :as db]
            [dato.lib.transit :as dato-transit]
            [goog.Uri])
  (:require-macros [cljs.core.async.macros :as async :refer [alt! go go-loop]])
  (:import [goog Uri]))

(defn chan? [value]
  (satisfies? cljs.core.async.impl.protocols/ReadPort value))

(defonce network-enabled?
  (atom true))

(set! (.-networkEnabled js/window) network-enabled?)

;; Extend protocols for transit/datascript reasons.  This should
;; *possibly* be pushed off to user-land code, but needs to be there
;; to optimize (not solve) Datomic/DataScript compatibility.
(extend-type goog.Uri
  IComparable
  (-compare [x y]
    (compare (.toString x) (.toString y))))

(def transit-reader
  (transit/reader :json
                  {:handlers
                   {"r"                (fn [uri] (Uri. uri))
                    "datascript/Datom" dc/datom-from-reader}}))

(def transit-writer
  (transit/writer :json
                  {:handlers
                   {goog.Uri                   (dato-transit/URIHandler.)
                    datascript.core/Datom      (dato-transit/DatomHandler.)
                    datascript.btset/BTSetIter (transit/VectorHandler.) }}))

(defn tx-report->transaction [report]
  (mapv dsu/datom->transaction (:tx-data report)))

(defn bootstrapped? [db]
  (dsu/qe-by db :dato.meta/bootrapped? true))

(defn new-dato [dato-host dato-path app-db cs-schema]
  (let [cast-bootstrapped? (atom false)
        ws-ch              (async/chan)
        dato-ch            (async/chan)
        ss-cast!           #(put! ws-ch %)
        pending-rpc        (atom {})
        ws                 (ws/build dato-host dato-path {:on-open    (fn [event] (js/console.log ":on-open :" event))
                                                          :on-close   (fn [event] (js/console.log ":on-close :" event))
                                                          :on-message (fn [event]
                                                                        (js/console.log "Bla: " event)
                                                                        (let [fr (js/FileReader. (.-data event))]
                                                                          (aset fr "onloadend" (fn [e]
                                                                                                 (let [data (transit/read transit-reader (.. e -target -result))]
                                                                                                   (js/console.log "Bla 2: " data)
                                                                                                   (if-let [cb-id (:server/rpc-id data)]
                                                                                                     (let [cb-or-ch (get @pending-rpc cb-id)]
                                                                                                       (js/console.log "pending-rpc: " @pending-rpc)
                                                                                                       (swap! pending-rpc dissoc cb-id)
                                                                                                       ;; Should we just pass the whole payload through, or just the data/resultts?
                                                                                                       (cond
                                                                                                         (chan? cb-or-ch) (put! cb-or-ch (:data data))
                                                                                                         (fn? cb-or-ch)   (cb (:data data))
                                                                                                         :else            nil))
                                                                                                     (ss-cast! data)))))
                                                                          (.readAsText fr (.-data event))))
                                                          :on-error   (fn [event] (js/console.log ":on-error :" event))})
        dato-send!         (fn [event data]
                             (js/console.log "dato-send! " (pr-str event) (pr-str data))
                             (.send ws (transit/write transit-writer [event data])))
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
                                (do
                                  (js/console.log "cast! " (pr-str payload))
                                  (put! dato-ch payload))
                                (js/console.log "Not yet bootstrapped, ignoring cast"))
                              true))
        ss-data            (atom {})
        ss-api             (atom {})
        ;; ???: Do the perf implications of anonymous functions mean these
        ;; shouldn't be in a tight loop?
        fn-desc->rpc       (fn fn-desc->rpc [remote-name desc]
                             (fn [& all-args]
                               ;; TODO: Check if last argument is a channel, an
                               (let [cb-or-ch (last all-args)
                                     cb       (cond
                                                (fn? cb-or-ch)   cb-or-ch
                                                (chan? cb-or-ch) #(put! cb-or-ch %)
                                                :else            false)
                                     args     (if cb
                                                (drop-last 1 all-args)
                                                all-args)
                                     uuid     (d/squuid)]
                                 (when (fn? cb)
                                   (swap! pending-rpc assoc uuid cb-or-ch))
                                 (dato-send! remote-name {:args   args
                                                          :rpc/id uuid})
                                 (if (chan? cb-or-ch)
                                   cb-or-ch
                                   uuid))))]
    {:comms    comms
     ;; Just for debugging to simulate messages coming in from the
     ;; server
     :ss-cast! ss-cast!
     :ws       {:ch    ws-ch
                :host  dato-host
                :path  dato-path
                :send! dato-send!
                :ws    ws}
     :db       app-db
     ::ss-dato ss-data
     :ss       ss-api
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
                               (js/console.log "cast!")
                               (cast! {:event :db/updated
                                       :data  {:intent intent
                                               :tx     (with-meta tx (merge meta {:tx/cb cb}))}})))
                :cast!      cast!
                :broadcast! (fn broadcast!
                              ([event]
                               (broadcast! event nil nil))
                              ([event payload]
                               (broadcast! event payload nil))
                              ([event payload meta]
                               (js/console.log "broadcast! " (pr-str event) (pr-str payload))
                               (dato-send! event payload)
                               true))
                :bootstrap! (fn []
                              (go
                                (js/console.log "Dato bootstrapping")
                                ;; Bootstrap us first
                                (loop []
                                  (when-not (= (.-readyState ws) 1)
                                    (<! (async/timeout 250))
                                    (js/console.log "Dato WebSocket not ready (" (.-readyState ws) "), waiting...")
                                    (recur)))
                                (dato-send! :ss/bootstrap {})
                                (js/console.log "1. Bootstrap")
                                (let [{:keys [data] :as -bootstrap} (loop [msg (<! ws-ch)]
                                                                      (if (= :ss/bootstrap-succeeded (:event msg))
                                                                        msg
                                                                        ;; Not the
                                                                        ;; message
                                                                        ;; we're
                                                                        ;; looking for
                                                                        ;; (could be
                                                                        ;; something
                                                                        ;; during
                                                                        ;; refreshing
                                                                        ;; with
                                                                        ;; figwheel. Put
                                                                        ;; the message
                                                                        ;; back on,
                                                                        ;; wait a few
                                                                        ;; ms, and try
                                                                        ;; again. Still very racy.
                                                                        (do
                                                                          (put! ws-ch msg)
                                                                          (<! (async/timeout 16))
                                                                          (recur (<! ws-ch)))))
                                      _ (js/console.log "2. Bootstrap; " data)
                                      {:keys [session-id schema]}      data]
                                  (swap! ss-data merge (:ss data))
                                  (reset! ss-api (->> (:ss data)
                                                      (map (fn [[k v]]
                                                             {(keyword (name k)) (fn-desc->rpc k v)}))
                                                      (reduce merge {})))
                                  (js/console.log "3. bootstrap: " app-db)
                                  (js/console.log "4. " @ss-api)
                                  ;; Do something with data
                                  (let [db (let [bootstrapped? (bootstrapped? @app-db)
                                                 conn          (if bootstrapped?
                                                                 app-db
                                                                 (d/create-conn (merge schema cs-schema)))]
                                             (def --fconn conn)
                                             ;; Check if we're reloading
                                             ;; with a pre-existing db
                                             (when-not bootstrapped?
                                               ;;(js/console.log "conn: " (pr-str conn))
                                               (remove-watch app-db :global-listener)
                                               (reset! app-db @conn)
                                               (js/console.log "me")
                                               (doto app-db
                                                 (db/setup-listener! :global-listener))
                                               (d/listen! app-db :server-tx-report
                                                          (fn [tx-report]
                                                            (let [datoms (tx-report->transaction tx-report)]
                                                              (js/console.log "Listened TX: " tx-report)
                                                              (when (or (get-in tx-report [:tx-meta :tx/broadcast?])
                                                                        (get-in tx-report [:tx-meta :tx/persist?]))
                                                                (js/console.log "Prep that shit!")
                                                                (let [prepped (db/prep-broadcastable-tx-report tx-report)]
                                                                  (when @network-enabled?
                                                                    (js/console.log "Send that tx-shit!")
                                                                    (dato-send! :ss/tx-requested prepped)))))))
                                               (js/console.log "too: " (pr-str (type app-db)))
                                               (let [local-session-id (d/tempid :db.part/user)]
                                                 (d/transact! app-db [{:db/id                 (d/tempid :db.part/user)
                                                                       :dato.meta/bootrapped? true}
                                                                      ;; TODO: This can likely be moved out to a user-land handler
                                                                      (assoc (:session data) :db/id local-session-id)
                                                                      {:local/current-session {:db/id local-session-id}}]))
                                               (js/console.log "app-db: " app-db)
                                               (reset! cast-bootstrapped? true))
                                             conn)]
                                    (js/console.log "Something")
                                    (put! dato-ch [:dato/bootstrap-succeeded session-id])
                                    (loop []
                                      (let [msg (<! ws-ch)]
                                        ;; Should this be more sophisticated?
                                        (put! dato-ch msg)
                                        (recur)))))))}}))

;; TODO: Probably convert to cljs Record to reify this stuff + avoid the apply shortcut
(defn bootstrap! [dato & args]
  (js/console.log "bootstrap!")
  (apply (get-in dato [:api :bootstrap!]) args))

(defn db [dato]
  (:db dato))

;; Do these need special-cased? Can just be exposed as built-in
;; server-side methods?
(defn r-qes-by [dato data]
  ((get-in dato [:api :r-qes-by]) data))

(defn r-pull [dato data]
  ((get-in dato [:api :r-pull]) data))

(defn r-transact! [dato data]
  (get-in dato [:api :r-transact]) data)

(defn transact! [dato & args]
  (js/console.log "transact!")
  (apply (get-in dato [:api :transact!]) args))

(defn me [conn]
  (dsu/qe-by conn :user/me? true))

(defn local-session [conn]
  (d/entity conn (dsu/val-by conn :local/current-session)))

(defn ss [dato]
  @(:ss dato))

(defn start-loop! [dato]
  (let [dato-ch (get-in dato [:comms :dato])
        conn    (get-in dato [:db])
        ;; TODO: This is messy, clean up once api and ss are merged
        ;; concepts
        api     (get-in dato [:api])
        ss      (get-in dato [:ss])
        context (merge api {:ss ss})]
    (go
      (loop []
        (alt!
          dato-ch ([{:keys [sender event data] :as payload}]
                   (cond
                     ;; Special-case waiting on https://github.com/tonsky/datascript/issues/76 and
                     ;; https://github.com/tonsky/datascript/issues/99
                     ;; Can eliminate this branch afterwards
                     (= :server/database-transacted event)
                     (let [previous-state @conn]
                       (def f-tx data)
                       ;; This is the abstraction not yet supported
                       ;; (two-phase commit, plus intermediate
                       ;; processing)
                       (db/consume-foreign-tx! conn data)
                       (con/effect! context previous-state @conn payload))
                     :else
                     (let [previous-state @conn
                           tx-data        (con/transition @conn payload)
                           tx-meta        (meta tx-data)]
                       (js/console.log "dato-keys: " (pr-str (keys payload)))
                       (js/console.log "\tevent: " (pr-str event))
                       (js/console.log "\ttx-data: " (pr-str tx-data))
                       (when tx-data
                         (d/transact! conn tx-data (if tx-meta
                                                     tx-meta
                                                     {:transient? true})))
                       (con/effect! context previous-state @conn payload)))
                   (recur)))))))
