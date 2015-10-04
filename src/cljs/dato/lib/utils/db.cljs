(ns dato.lib.utils.db
  (:require [cljs-http.client :as http]
            [cljs.core.async :as async]
            [datascript :as d]
            [dato.lib.utils :as utils])
  (:require-macros [cljs.core.async.macros :refer (go)]))

(defn fast-guid [db e]
  (:v (first (d/datoms db :eavt e :dato/guid))))

(defn find-guid [tx-report e]
  (or (fast-guid (:db-after tx-report) e)
      (fast-guid (:db-before tx-report) e)))

(defn add-entity-guid [guid-map tx-report e]
  (if (contains? guid-map e)
    guid-map
    ;; TODO: write retractEntity fn that preserves fid
    (assoc guid-map e (find-guid tx-report e))))

(defn add-ref-guid [guid-map tx-report a v]
  (if (= :db.type/ref (get-in tx-report [:db-after :schema a :db/valueType]))
    (if (contains? guid-map v)
      guid-map
      (assoc guid-map v (find-guid tx-report v)))
    guid-map))

(defn guid-map [tx-report]
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

(defn datom->tx [tempids schema datom]
  [(if (:added datom)
     :db/add
     :db/retract)
   (get tempids (:e datom) (:e datom))
   (:a datom)
   (if (ref? schema (:a datom))
     (get tempids (:v datom) (:v datom))
     (:v datom))])

(defn handle-transaction [conn {:keys [datoms tempid->fid]}]
  (let [schema (:schema @conn)
        {adds true retracts false} (group-by :added datoms)
        {:keys [tempids]} (d/transact! conn (map (fn [[tempid fid]]
                                                   {:db/id tempid
                                                    :fid fid})
                                                 tempid->fid))]
    (d/transact! conn (concat (map (partial datom->tx tempids schema) retracts)
                              (vals (reduce (fn [acc d]
                                              (update acc (:e d) (fn [m]
                                                                   (assoc m
                                                                          :db/id (get tempids (:e d) (:e d))
                                                                          (:a d) (if (ref? schema (:a d))
                                                                                   (get tempids (:v d) (:v d))
                                                                                   (:v d))))))
                                            {} adds))))))
