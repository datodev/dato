(ns dato.lib.server
  (:require [clojure.core.async :as casync]
            [cognitect.transit :as transit]
            [datomic.api :as d]
            [dato.db.utils :as dsu]
            [immutant.codecs :as cdc]
            [immutant.web :as iw]
            [immutant.web.async :as async]
            [immutant.web.middleware :as imw]
            [immutant.codecs.transit :as it]
            [dato.lib.incoming :as incoming]
            [dato.lib.datomic :as datod])
  (:import [java.net URI URLEncoder]
           [java.util UUID]))

(defprotocol DatomImpl
  (transit-rep [d])
  (->tx [d]))

(defrecord Datom [e a v t added]
  DatomImpl
  (transit-rep [_]
    [e a v t added]))

(defn datascript-datom->datom [v]
  (apply ->Datom v))

(it/register-transit-codec
 :read-handlers (merge transit/default-read-handlers {"r"                (transit/read-handler (fn [s] (URI. s)))
                                                      "datascript/Datom" (transit/read-handler datascript-datom->datom)})
 :write-handlers (merge transit/default-write-handlers {dato.lib.server.Datom
                                                        (transit/write-handler (constantly "datascript/Datom")
                                                                               transit-rep)}))

(defn dconn [dato-server]
  (d/connect (:datomic-uri dato-server)))

(defn ddb [dato-server]
  (d/db (dconn dato-server)))

(defn user-db
  "Currently a placeholder for a filtered-db with only datoms the
  given user is allowed to *read* (writing security is separate)8"
  [db session]
  db)

(defn ss-msg [event-name data]
  {:namespace :server
   :sender    :server
   :event     event-name
   :data      data})

(defonce all-sessions
  (atom {}))

(defn dato-session [session]
  (get-in @all-sessions [(:id session)]))

(defn session-server [session]
  (let [full-session (if (:live session)
                       session
                       (dato-session session))
        server (get-in full-session [:live :dato :server])]
    (if (var? server)
      @server
      server)))

(defn session-routes [session]
  (let [server (session-server session)
        routes (:routing-table server)]
    (if (var? routes)
      @routes
      routes)))

(defn session-db [session]
  (let [server (session-server session)]
    (def -session-db-session session)
    (def -session-db-server server)
    (ddb server)))

(defn serializable-sessions [all]
  (reduce into (map (fn [[k v]] {k (dissoc v :live)}) all)))

(defonce tx-report-ch
  (casync/chan))

(defonce tx-report-mult
  (casync/mult tx-report-ch))

(defn setup-tx-report-ch [conn]
  (let [queue (d/tx-report-queue conn)]
    (def report-future
      (future
        (while true
          (let [tx (.take queue)]
            (assert (casync/put! tx-report-ch tx)
                    "Cant put transaction on tx-report-ch")))))))

(defn broadcast-tx! [dato-server sessions tx-report]
  (def last-tx-report tx-report)
  (def l-dato-server dato-server)
  (let [data        (incoming/outgoing-tx-report tx-report)
        _           (def last-tx-data data)
        payload     (ss-msg :server/database-transacted data)
        enc-payload (cdc/encode payload :transit)]
    (doseq [[_ session] sessions]
      (let [ch (get-in session [:live :ch])]
        (async/send! ch enc-payload)))))

(defn start-tx-broadcast! [dato-server tx-mult]
  (let [stop-ch (casync/chan)
        tx-ch   (casync/chan)]
    (casync/tap tx-mult tx-ch)
    (casync/go
      (loop []
        (casync/alt!
          tx-ch ([tx-report]
                 (println "TX-Broadcast Datoms:")
                 (broadcast-tx! dato-server @all-sessions tx-report)
                 (recur))
          stop-ch ([]
                   (println "Exiting tx-broadcast go-loop")
                   (casync/untap tx-mult tx-ch)))))
    stop-ch))

(defn datomic-db->datomic-schema [db]
  (->> (d/q '[:find [?e ...]
              :where
              [?e :db/ident ?ident]]
            db)
       (map (partial datod/entity+ db))
       (map datod/touch+)
       (filter (fn [ent]
                 (let [kns (namespace (:db/ident ent))
                       kns (subs kns 0 (min 2 (count kns)))]
                   (and (not= "db" kns)
                        (not (get #{"fressian"} (namespace (:db/ident ent))))))))
       (sort-by :db/ident)
       (reduce (fn [run entry]
                 (assoc run (:db/ident entry) entry)) {})))

(defn on-open [dato-config ch]
  (let [oc               (async/originating-request ch)
        session          (:session oc)
        id               (:id session)
        others           @all-sessions
        _                (def -last-config dato-config)
        server           (if (var? (:server dato-config))
                           @(:server dato-config)
                           (:server dato-config))
        user-db-ids      (mapv :db/id (dsu/qes-by (ddb server) :user/email))
        full-session     {:live            {:ch   ch
                                            :dato dato-config}
                          :id              id
                          :session/key     id
                          :session/user-id (rand-nth user-db-ids)}
        others-data      (cdc/encode (ss-msg :server/session-created {:session (dissoc full-session :live)}) :transit)
        full-session-enc (cdc/encode (dissoc full-session :live) :transit)]
    ;; Put new channel in global storage
    (swap! all-sessions assoc id full-session)))

(defn on-close [ch {:keys [code reason]}]
  (let [oc      (async/originating-request ch)
        session (:session oc)
        id      (:id session)
        others  (dissoc @all-sessions id)
        data    (cdc/encode {:event  :server/session-destroyed
                             :sender :server
                             :data   {:session/key id}}
                            :transit)]
    (println "Closed, terminating session id: " id)
    (swap! all-sessions dissoc id)
    ;;(broadcast-user-org! @all-sessions session data)
    ))

(defn default-handler [session incoming meta]
  (println "Unhandled message: " (pr-str incoming)))

(defn on-message [ch msg]
  (let [oc               (async/originating-request ch)
        session          (:session oc)
        full-session     (dato-session session)
        routing-table    (session-routes full-session)
        id               (:id session)
        my-ch            (get-in @all-sessions [id :live :ch])
        others           (dissoc @all-sessions id)
        _                (println "Message data:" (pr-str msg))
        raw-incoming     (cdc/decode (.getBytes msg) :transit)
        [event msg-data] raw-incoming
        args             (or (:args msg-data) [:place-holder])
        ;; :place-holder is to prever failure for e.g. (log-out!),
        ;; otherwise (:args msg-data) will be nil
        rpc?             (boolean args)
        _                (println "raw-incoming ffs: " (pr-str raw-incoming))
        _                (println "\t: " (pr-str [event] :handler))
        _                (println "Routes availabe in table: " (pr-str routing-table))
        _                (def -last-session session)
        _                (println "\targs: " args)
        handler          (get-in routing-table [[event] :handler] default-handler)
        ;; Temp hackishness
        handler          (if (var? handler)
                           @handler
                           handler)
        data             (if rpc?
                           {:server/rpc-id (:rpc/id msg-data)
                            :data          (apply (partial handler session all-sessions) args)}
                           (apply (partial handler session all-sessions) (or args)))
        _                (println "data: " (pr-str data))
        encoded          (cdc/encode data :transit)]
    (when data
      (async/send! my-ch encoded))))

(defn broadcast-other-users! [sessions session data]
  (let [full-session (dato-session session)
        user-id      (get-in full-session [(:session/user-id full-session)])
        channels     (-> sessions
                         (dissoc (:id full-session))
                         (vals)
                         (->>
                          (remove #(= (:session/user-id %) user-id))
                          (map #(get-in % [:live :ch]))))]
    (println "broadcast-other-users! " (pr-str data))
    (doseq [ch channels]
      (when (async/open? ch)
        (async/send! ch data)))))

(defn bootstrap-user [session _ _]
  (let [my-session-id (get-in @all-sessions [(:id session) :session/key])
        full-session  (dato-session session)
        db            (session-db full-session)
        server        (session-server session)
        ss            (->> (session-routes full-session)
                           (filter (fn [[k v]] (= "ss" (namespace (first k)))))
                           (mapv (fn [[k v]] {(first k) (dissoc v :handler)}))
                           (reduce merge {}))]
    (ss-msg :ss/bootstrap-succeeded
            {:session (dissoc full-session :live)
             :ss      ss
             :schema  (datomic-db->datomic-schema db)})))

(defn update-session [my-session _ data]
  ;; XXX update-in is dangerous here, we're allowing any key the user
  ;; encodes in transit to be inserted here and to get broadcast out
  ;; to other users. Should apply a whitelist approach.
  (let [new-session (-> (swap! all-sessions update-in [(:id my-session)] merge data)
                        (get (:id my-session))
                        (dissoc :live))
        data        (ss-msg :server/session-updated
                            {:session new-session})
        payload     (cdc/encode data :transit)
        others      (dissoc @all-sessions (:id my-session))]
    (doseq [[_ session] others]
      (let [ch (get-in session [:live :ch])]
        (async/send! ch payload)))))

(defn r-q [session _ incoming]
  (println "r-q" (pr-str incoming))
  (let [q-name        (:name incoming)
        datalog-query (:q incoming)
        query-args    (:args incoming)
        data          (apply d/q datalog-query
                             (session-db (dato-session session))
                             query-args)]
    (ss-msg (keyword "server" (str (name q-name) "-succeeded"))
            {:results data})))

(defn r-pull [session _ incoming]
  (println "R-pull! " (pr-str incoming))
  (let [p-name  (:name incoming)
        pattern (incoming/ensure-pull-required-fields (:pull incoming))
        data    (d/pull (session-db (dato-session session))
                        pattern
                        (:lookup incoming))]
    (ss-msg (keyword "server" (str (name p-name) "-succeeded"))
            {:results data})))

(defn r-qes-by [session _ incoming]
  (let [qes-name (:name incoming)
        data     (->> (if (:v incoming)
                        (dsu/qes-by (session-db (dato-session session))
                                    (:a incoming)
                                    (:v incoming))
                        (dsu/qes-by (session-db (dato-session session))
                                    (:a incoming)))
                      (mapv dsu/touch+))]
    (ss-msg (keyword "server" (str (name qes-name) "-succeeded"))
            {:results data})))


(defn handle-transaction [session _ raw]
  (println "WTF handle-transaction: ")
  (println "\t " session)
  (println "\t " raw)
  (def l-raw raw)
  (let [incoming     raw
        _            (def l-incoming incoming)
        full-session (dato-session session)
        dato-server  (session-server full-session)
        _            (def l-session session)
        _            (def l-dato-server dato-server)
        conn         (dconn dato-server)
        datoms       (:tx-data incoming)
        tx-guid      (:tx/guid incoming)
        meta         (assoc (:tx-meta incoming) :tx/guid tx-guid)
        fids         (:fids incoming)
        db           (ddb dato-server)
        full-session (dato-session session)
        user         (d/entity db (:session/user-id full-session))
        ]
    ;; If it's marked with persist, we save it and let the tx-listener broadcast out the result
    ;; Else if we see it but it's not marked with persisted,
    ;; the intent must be to transact it into the in-memory datomic instance, and broadcast out the result of that
    (cond
      (:tx/persist? meta) (incoming/handle-transaction conn meta datoms fids db user)
      ;;(:tx/persist meta) (incoming/handle-transaction conn datoms fids db user)
      )))

(def default-routing-table
  {[:session/updated] {:handler #'update-session}
   [:ss/tx-requested] {:handler #'handle-transaction}
   [:ss/r-q]          {:handler #'r-q}
   [:ss/r-qes-by]     {:handler #'r-qes-by}
   [:ss/r-pull]       {:handler #'r-pull}
   [:ss/bootstrap]    {:handler #'bootstrap-user}})

(defn new-routing-table [table]
  (merge default-routing-table table))

(defn new-session-id []
  (str (UUID/randomUUID)))

(defrecord DatoServer [routing-table datomic-uri]  )

(defn ?assign-id [handler dato-server]
  (fn [request]
    (let [id        (get-in request [:session :id] (new-session-id))
          mouse-pos [0 0]
          response  (handler (-> request
                                 (assoc-in [:session :id] id)
                                 (assoc-in [:session :mouse :pos] mouse-pos)
                                 (assoc-in [:session :live :dato] dato-server)))]
      response)))

(defn start! [handler config]
  (def -config config)
  (let [dato-server (:server config)]
    (def iw-handler
      (iw/run
        (-> handler
            (imw/wrap-websocket {:on-message (var on-message)
                                 :on-open    (partial on-open config)
                                 :on-close   (var on-close)})
            (?assign-id dato-server)
            (imw/wrap-session))
        {:path "/ws"
         :host "0.0.0.0"}))
    (setup-tx-report-ch (dconn @dato-server))
    (def stop-tx-broadcast-ch (start-tx-broadcast! dato-server tx-report-mult))))
