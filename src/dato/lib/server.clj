(ns dato.lib.server
  (:require [clojure.core.async :as casync]
            [clojure.tools.logging :as log]
            [cognitect.transit :as transit]
            [datascript.core]
            [dato.db.utils :as dsu]
            [dato.lib.datomic :as datod]
            [dato.web-peer.incoming :as web-peer-incoming]
            [dato.web-peer.outgoing :as web-peer-outgoing]
            [datomic.api :as d]
            [immutant.codecs :as cdc]
            [immutant.codecs.transit :as it]
            [immutant.web :as iw]
            [immutant.web.async :as async]
            [immutant.web.middleware :as imw]
            [talaria.api :as tal]
            [talaria.routes :as tal-routes])
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

;; TODO: this might be unnecessary, since talaria handles encoding messages
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
  given user is allowed to *read* (writing security is separate)"
  [db session]
  db)

(defn ss-msg [event-name data]
  {:namespace :server
   :sender    :server
   :event     event-name
   :op        event-name
   :data      data})

(defonce all-sessions ^{:doc "Map of session id to session data"}
  (atom {}))

(defn all-channel-ids [session-store]
  (keys @session-store))

;; ??? Should this take session-store or dato-state
(defn get-session [session-store session-id]
  (get @session-store session-id))

(defn serializable-sessions [all]
  (reduce into (map (fn [[k v]] {k (dissoc v :live)}) all)))

(defn unwrap-var [maybe-var]
  (if (var? maybe-var)
    @maybe-var
    maybe-var))

(defn get-routing-table [dato-config]
  (-> dato-config :server unwrap-var :routing-table unwrap-var))

(defn get-dato-db [dato-config]
  (ddb (unwrap-var (:server dato-config))))

(defn get-dato-conn [dato-config]
  (dconn (unwrap-var (:server dato-config))))

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

(defn broadcast-tx! [tal-state session-store tx-report]
  (def last-tx-report tx-report)
  (let [data          (web-peer-outgoing/convert-tx-report tx-report)
        _             (def last-tx-data data)
        message       (ss-msg :server/database-transacted data)
        tx-session-id (:tx/session-id (web-peer-outgoing/tx-ent tx-report))]
    (doseq [ch-id (all-channel-ids session-store)
            :when (not= ch-id tx-session-id)]
      (tal/queue-msg! tal-state ch-id message))))

(defn start-tx-broadcast! [tal-state session-store tx-mult]
  (let [stop-ch (casync/chan)
        tx-ch   (casync/chan)]
    (casync/tap tx-mult tx-ch)
    (casync/go
      (loop []
        (casync/alt!
          tx-ch ([tx-report]
                 (broadcast-tx! tal-state session-store tx-report)
                 (recur))
          stop-ch ([]
                   (println "Exiting tx-broadcast go-loop")
                   (casync/untap tx-mult tx-ch)))))
    stop-ch))

(defmulti handle-websocket-msg (fn [tal-state dato-config session-store msg] (:op msg)))

(defmethod handle-websocket-msg :default
  [tal-state dato-config session-store msg]
  (log/infof "Unhandled message: %s" (pr-str (dissoc msg :tal/ring-req :tal/ch))))

(defn start-tal-handler [tal-state dato-config session-store]
  (future (let [q (tal/get-recv-queue tal-state)]
            (while true
              (let [msg (.take q)]
                (try
                  (handle-websocket-msg tal-state dato-config session-store msg)
                  (catch Exception e
                    (log/error e "Error in tal handler"))
                  (catch AssertionError e
                    (log/error e "Error in tal handler"))))))))

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

(defmethod handle-websocket-msg :tal/channel-open
  [tal-state dato-config session-store msg]
  (let [id           (:tal/ch-id msg)
        server       (unwrap-var (:server dato-config))
        user-db-ids  (mapv :db/id (dsu/qes-by (ddb server) :user/email))
        session {:id              id
                 :session/key     id
                 :session/user-id (rand-nth user-db-ids)}]
    (swap! all-sessions assoc id session)))


(defmethod handle-websocket-msg :tal/channel-closed
  [tal-state dato-config session-store msg]
  (let [id (:tal/ch-id msg)]
    (log/infof "Closed, terminating session id: %s" id)
    (swap! session-store dissoc id)))

(defn default-handler [dato-state session-id data]
  (log/infof "Unhandled message: %s" (pr-str data)))

(defmethod handle-websocket-msg ::dato-route
  [tal-state dato-config session-store msg]
  (let [routing-table (get-routing-table dato-config)
        event         (:op msg)
        msg-data      (:data msg)
        handler       (get-in routing-table [[event] :handler] default-handler)
        ;; temp hackishness
        handler       (unwrap-var handler)
        session-id    (:tal/ch-id msg)
        dato-state    {:tal-state tal-state
                       :dato-config dato-config
                       :session-store all-sessions}
        data          (apply handler dato-state session-id (or (:args msg-data) [:place-holder]))
        _             (log/infof "data: %s" (pr-str data))]
    (tal/queue-reply! tal-state msg data)))

(defmethod handle-websocket-msg :web-peer/transact
  [tal-state dato-config session-store msg]
  (let [reply (web-peer-incoming/handle-txes (get-dato-conn dato-config)
                                             {:tx/session-id (:tal/ch-id msg)}
                                             (-> msg :data :txes)
                                             (-> msg :data :guids)
                                             (get-dato-db dato-config)
                                             nil)]
    (tal/queue-reply! tal-state msg reply)))


(defn broadcast-other-users! [dato-state session-id data]
  (log/infof "broadcast-other-users! %s" (pr-str data))
  (doseq [ch-id (all-channel-ids (:session-store dato-state))
          :when (not= ch-id session-id)]
    (tal/queue-msg! (:tal-state dato-state) ch-id data)))

(defn bootstrap-user [dato-state session-id _]
  (let [db (get-dato-db (:dato-config dato-state))
        ss (->> (get-routing-table (:dato-config dato-state))
                (filter (fn [[k v]] (= "ss" (namespace (first k)))))
                (mapv (fn [[k v]] {(first k) (dissoc v :handler)}))
                (reduce merge {}))]
    (ss-msg :ss/bootstrap-succeeded
            ;; TODO: should probably run session through a filter of whitelisted keys
            {:session (get-session (:session-store dato-state) session-id)
             :ss      ss
             :schema  (datomic-db->datomic-schema db)})))

(defn update-session [dato-state session-id data]
  ;; XXX update-in is dangerous here, we're allowing any key the user
  ;; encodes in transit to be inserted here and to get broadcast out
  ;; to other users. Should apply a whitelist approach.
  (let [new-session (-> (swap! (:session-store dato-state) update session-id merge data)
                        (get session-id))
        data        (ss-msg :server/session-updated
                            {:session new-session})]
    (broadcast-other-users! dato-state session-id data)))

(defn r-q [dato-state session-id incoming]
  (let [q-name        (:name incoming)
        datalog-query (:q incoming)
        query-args    (:args incoming)
        data          (apply d/q datalog-query
                             (get-dato-db (:dato-config dato-state))
                             query-args)]
    (ss-msg (keyword "server" (str (name q-name) "-succeeded"))
            {:results data})))

(defn r-pull [dato-state session-id incoming]
  (let [p-name  (:name incoming)
        pattern (web-peer-incoming/ensure-pull-required-fields (:pull incoming))
        data    (d/pull (get-dato-db (:dato-config dato-state))
                        pattern
                        (:lookup incoming))]
    (ss-msg (keyword "server" (str (name p-name) "-succeeded"))
            {:results data})))

(defn r-qes-by [dato-state session-id incoming]
  (let [qes-name (:name incoming)
        db       (get-dato-db (:dato-config dato-state))
        data     (->> (if (:v incoming)
                        (dsu/qes-by db
                                    (:a incoming)
                                    (:v incoming))
                        (dsu/qes-by db
                                    (:a incoming)))
                      (mapv dsu/touch+))]
    (ss-msg (keyword "server" (str (name qes-name) "-succeeded"))
            {:results data})))

(def default-routing-table
  {[:session/updated] {:handler #'update-session}
   [:ss/r-q]          {:handler #'r-q}
   [:ss/r-qes-by]     {:handler #'r-qes-by}
   [:ss/r-pull]       {:handler #'r-pull}
   [:ss/bootstrap]    {:handler #'bootstrap-user}})

(defn new-routing-table [table]
  (merge default-routing-table table))

(defrecord DatoServer [routing-table datomic-uri])

(defn validate-config [config]
  (assert (:server config) "config must have a server field")
  (assert (:port config) "config must have a port field"))

(defn wrap-server-routes [tal-state]
  (let [ws-setup (tal-routes/websocket-setup tal-state)
        ajax-poll (tal-routes/ajax-poll tal-state)
        ajax-send (tal-routes/ajax-send tal-state)]
    (fn [req]
      (case (:uri req)
        "/talaria" (ws-setup req)
        "/talaria/ajax-poll" (ajax-poll req)
        "/talaria/ajax-send" (ajax-send req)
        nil))))

(defn set-dato-route-heirarchy!
  "Sets route hierarchy so that the defmethod for ::dato-route will pick up all dato routes"
  [dato-routes]
  (doseq [route dato-routes
          :let [route-kw (ffirst route)]]
    (derive route-kw ::dato-route)))

(defn start! [handler config]
  (def -dato-config config)
  (validate-config config)
  (let [dato-server (:server config)
        tal-state (tal/init :transit-reader-opts {:handlers {"r"                (transit/read-handler (fn [s] (URI. s)))
                                                             "datascript/Datom" (transit/read-handler datascript-datom->datom)}}
                            :transit-writer-opts {:handlers {;; Do we still need this one?
                                                             dato.lib.server.Datom (transit/write-handler (constantly "datascript/Datom")
                                                                                                          transit-rep)
                                                             datascript.core.Datom
                                                             (transit/write-handler (constantly "datascript/Datom")
                                                                                    (fn [d] [(:e d) (:a d) (:v d) (:tx d) (:added d)]))}})
        session-store all-sessions]
    (set-dato-route-heirarchy! (get-routing-table config))
    (def -tal-state tal-state)
    (def -dato-server
      (iw/run
        (-> (wrap-server-routes tal-state)
            (tal-routes/wrap-session-id)
            (imw/wrap-session))
        {:path "/"
         :host "0.0.0.0"
         :port (:port config)}))
    (def -tal-handler (start-tal-handler tal-state config session-store))
    (setup-tx-report-ch (dconn @dato-server))
    (def stop-tx-broadcast-ch (start-tx-broadcast! tal-state session-store tx-report-mult))))
