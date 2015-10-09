(ns dato.lib.web-peer
  (:require [cljs.core.async :as async]
            [cljs-http.client :as http]
            [clojure.data :as data]
            [datascript :as d]
            [dato.lib.utils :as utils]
            [dato.lib.utils.datascript :as ds-utils]
            [dato.lib.utils.db :as db-utils]
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
                          (let [actual-report (db-utils/handle-transaction db-conn server-reply)]
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
                                :guids (db-utils/guid-map optimistic-report)
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
