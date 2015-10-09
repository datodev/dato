(ns dato.lib.web-peer
  (:require [cljs.core.async :as async]
            [cljs-http.client :as http]
            [clojure.data :as data]
            [datascript :as d]
            [dato.lib.utils :as utils]
            [dato.lib.utils.datascript :as ds-utils]
            [dato.lib.utils.queue :as queue])
  (:require-macros [cljs.core.async.macros :refer (go)]))

;; TODOS:
;;  1. Figure out how to execute transact over the websocket
;;  2. Figure how how new data is transacted into the ds db
;;  3. What should the send-fn API look like?

(defn send-txes-to-server [db-conn send-fn item]
  (let [exploded-txes (->> (ds-utils/explode-tx-data @db-conn (:txes item))
                           ;; Have to pull out tempids so that the server will know which
                           ;; frontend ids go with which id. Not a great approach :/
                           (map (fn [tx] (update tx 1 #(get-in item [:optimistic-report :tempids %] %)))))]
    ;; TODO: is send-fn doing what I expect?
    (send-fn {:txes exploded-txes
              :guids (:guids item)})))

(defn handle-report-diff [conn txes optimistic-report actual-report]
  (let [optimistic-txes (set (map (fn [d] [(:e d) (:a d) (:v d)]) (:tx-data optimistic-report)))
        actual-txes (set (map (fn [d] [(:e d) (:a d) (:v d)]) (:tx-data actual-report)))
        [only-optimistic only-actual _] (data/diff optimistic-txes actual-txes)]
    (utils/mlog txes optimistic-txes actual-txes)
    (utils/mlog "Only optimisitic:" only-optimistic)
    (utils/mlog "Only actual:" only-actual)))

(defn get-lock-atom [db-conn]
  (-> db-conn meta ::tx-queue :lock))

(defn get-queue-atom [db-conn]
  (-> db-conn meta ::tx-queue :queue))

(defn get-sending-atom [db-conn]
  (-> db-conn meta ::tx-queue :sending))

;; TODO: shutdown should check that there aren't still items on the queue
(defn lock-send-queue [db-conn]
  (compare-and-set! (get-lock-atom db-conn) nil (js/Date.)))

(defn unlock-send-queue [db-conn]
  (reset! (get-lock-atom db-conn) nil))

;; Requirements
;;  1. Send synchronously
;;  2. deref-db needs to be able to apply unsent items to the db
;;      a. access to everything in the queue
;;      b. access to what is currently being sent
;; What are we going to do with txes from the server?
;;   Should we apply them in server transaction order? Probably.

(defn fast-guid
  "Looks up dato/guid for a given db and entity id"
  [db e]
  (:v (first (d/datoms db :eavt e :dato/guid))))

(defn find-guid
  "Looks up dato/guid for a given tx-report and entity id.
   Looks at both db-before and db-after, since the guid may have been
   added or retracted in this transaction."
  [tx-report e]
  (or (fast-guid (:db-after tx-report) e)
      (fast-guid (:db-before tx-report) e)))

(defn add-entity-guid
  "Adds dato/guid for the given entity id"
  [guid-map tx-report e]
  (if (contains? guid-map e)
    guid-map
    ;; TODO: write retractEntity fn that preserves fid
    (assoc guid-map e (find-guid tx-report e))))

(defn add-ref-guid
  "Adds dato/guid for the given value, if the value is a reference to another entity"
  [guid-map tx-report a v]
  (if (= :db.type/ref (get-in tx-report [:db-after :schema a :db/valueType]))
    (if (contains? guid-map v)
      guid-map
      (assoc guid-map v (find-guid tx-report v)))
    guid-map))

;; TODO: is there a way for entity ids to get to the backend without us knowing the dato/guid for them?
;;       It should be fine--if the FE doesn't know about an entity, then why would it need to send the dato/guid for it?
(defn make-guid-map
  "Returns a map from entity-id to dato/guid for all of the entities
   referenced in the transaction."
  [tx-report]
  (reduce (fn [acc {:keys [e a v]}]
            (-> acc
              (add-entity-guid tx-report e)
              (add-ref-guid tx-report a v)))
          {} (:tx-data tx-report)))

(defn ref? [schema attr]
  (some-> schema
    (get attr)
    :db/valueType
    (keyword-identical? :db.type/ref)))

(defn datom->tx
  "Converts datom to transaction that would create a similar datom
   e.g. {:e 1 :a :task/title :v \"hello\" :t 10 :added true} =>
        [:db/add 1 :task/title \"hello\"]"
  [tempids schema datom]
  [(if (:added datom)
     :db/add
     :db/retract)
   (get tempids (:e datom) (:e datom))
   (:a datom)
   (if (ref? schema (:a datom))
     (get tempids (:v datom) (:v datom))
     (:v datom))])

(defn handle-transaction
  "Puts datoms from the server into datascript database.
   tempid->guid mapping lets us resolve datoms between BE and FE without
   sharing db/id.
   Lots of unnecessary complexity ordering transactions, waiting on a
   fix for https://github.com/tonsky/datascript/issues/76"
  [conn {:keys [datoms tempid->guid]}]
  (let [schema (:schema @conn)
        {adds true retracts false} (group-by :added datoms)
        ;; TODO: need a better way to create the dato/guid -> db/id mapping
        ;; once tonsky/datascript#76 is fixed, should be able to use the
        ;; analog of dato.lib.incoming/fill-txes
        {:keys [tempids]} (d/transact! conn (map (fn [[tempid guid]]
                                                   {:db/id tempid
                                                    :dato/guid guid})
                                                 tempid->guid))
        txes (concat (map (partial datom->tx tempids schema) retracts)
                     (vals (reduce (fn [acc d]
                                     (update acc (:e d) (fn [m]
                                                          (assoc m
                                                                 :db/id (get tempids (:e d) (:e d))
                                                                 (:a d) (if (ref? schema (:a d))
                                                                          (get tempids (:v d) (:v d))
                                                                          (:v d))))))
                                   {} adds)))]
    ;; (cljs.pprint/pprint ["txes" txes])
    (d/transact! conn txes)))


(defn maybe-send-next-item
  "Acquires a lock and empties the queue, sending each item sequentially"
  [db-conn send-fn q]
  ;; TODO: is it possible for items to get stuck on the queue?
  ;; TODO: do lost connections need special logic here?
  (when (lock-send-queue db-conn)
    (go
      (async/<! (go
                  (loop [item (peek @q)]
                    (when item
                      (try
                        (let [server-reply (async/<! (send-txes-to-server db-conn send-fn item))]
                          ;; TODO: handle metadata on tx
                          (let [actual-report (handle-transaction db-conn server-reply)]
                            (handle-report-diff db-conn (:txes item) (:optimistic-report item) actual-report)
                            (when-let [cb (get-in item [:tx-meta :tx/cb])]
                              (try
                                (cb actual-report)
                                (catch js/Error e
                                  (utils/merror "Error in tx callback" e))))
                            ;; TODO: proper error handling
                            (assert (= item (queue/pop! q)) "send-queue lock must be broken, we lost some data!")))
                        (catch js/Error e
                          (let [item (queue/pop! q)]
                            ;; TODO: better error handling
                            (utils/mlog "Item that broke things:" item)
                            (utils/merror e))))
                      ;; TODO: potential for infinite loop here, if item isn't popped correctly
                      (recur (peek @q))))))
      ;; TODO: Need to check for empty queue and unlock queue in same operation
      (unlock-send-queue db-conn))))

(defn add-item-to-queue [db-conn item]
  (queue/enqueue! (get-queue-atom db-conn) item))

(defn deref-db [db-conn]
  (reduce (fn [db queued-tx]
            (d/db-with db (:txes queued-tx)))
          ;; TODO: is it enough to get items from the queue--are things in transit recorded there?
          @db-conn @(get-queue-atom db-conn)))

(defn transact [db-conn txes & [tx-meta]]
  (let [db (deref-db db-conn)
        optimistic-report (d/with db txes tx-meta)]
    (add-item-to-queue db-conn {:optimistic-report optimistic-report
                                :txes txes
                                :guids (make-guid-map optimistic-report)
                                :tx-meta tx-meta})
    ;; TODO: will this cause problems? Listeners might be called twice
    ;;       Could pass in an extra bit of data to indicate that it's optimistic?
    ;; Should only be called once if no conflicts?
    (doseq [[_ callback] @(:listeners (meta db-conn))]
      (callback optimistic-report))
    optimistic-report))

(defn add-web-peer-metadata [db-conn send-fn meta-data]
  (assert (empty? (::tx-queue meta-data)))
  (let [q (queue/new-queue)]
    (queue/add-consumer q (fn [q-atom] (maybe-send-next-item db-conn send-fn q-atom)))
    (assoc meta-data ::tx-queue {:queue q
                                 :sending (atom #{})
                                 :lock (atom nil)})))

(defn web-peerify-conn [db-conn send-fn]
  (alter-meta! db-conn (partial add-web-peer-metadata db-conn send-fn)))

(defn inspect-meta [db-conn]
  (js/console.log (dissoc (meta db-conn)
                          :listeners)))
