(ns dato.web-peer.outgoing
  (:require [clojure.string :as str]
            [datascript.core :as ds]
            [datomic.api :as d]
            [dato.db.utils :as dsu]))

;; What needs to happen to the outgoing datoms?
;; 1. Turn ent-id attrs into keyword attrs
;; 2. Turn enums into keywords
;; 3. Filter datoms without a dato/guid
;; 4. Filter datoms that the user doesn't have permission to see (not implemented for now)
;; 5. Replace entity ids with a tempid and create an guid-map
;; 6. Create a new datascript datom and drop the tx-id

(defn have-guid? [datom e->guid]
  (instance? java.util.UUID (e->guid (:e datom))))

(defn have-ref-guid? [datom e->guid]
  (instance? java.util.UUID (e->guid (:v datom))))

(defn cant-broadcast-reason [datom e->guid db-before db-after cust]
  (cond (= (:e datom) (:tx datom))
        :transaction-annotation

        (not (have-guid? datom e->guid))
        :missing-guid

        (and (dsu/ref-attr? db-after (:a datom))
             (not (have-ref-guid? datom e->guid)))
        :missing-guid-for-ref-value

        :else nil))

(defn group-datoms-by-cant-broadcast [datoms e->fid db-before db-after cust]
  (group-by (fn [d] (cant-broadcast-reason d e->fid db-before db-after cust)) datoms))

(defn fast-guid [db e]
  (:v (first (d/datoms db :eavt e :dato/guid))))

(defn find-guid [tx-report e]
  (or (fast-guid (:db-after tx-report) e)
      (fast-guid (:db-before tx-report) e)))

(defn add-entity-guid [guid-map tx-report e]
  (if (contains? guid-map e)
    guid-map
    (assoc guid-map e (find-guid tx-report e))))

(defn add-ref-guid [guid-map tx-report a v]
  (if (dsu/ref-attr? (:db-after tx-report) a)
    (if (contains? guid-map v)
      guid-map
      (assoc guid-map v (find-guid tx-report v)))
    guid-map))

(defn make-guid-map [tx-report]
  (reduce (fn [acc {:keys [e a v]}]
            (-> acc
              (add-entity-guid tx-report e)
              (add-ref-guid tx-report a v)))
          {} (:tx-data tx-report)))

(defn tx-ent [tx-report]
  (->> tx-report :tx-data first :tx (d/entity (:db-after tx-report))))

(defn make-datascript-datoms [filtered-datoms db e->guid]
  (let [eid->tempid (zipmap (keys e->guid) (map (comp - inc) (range)))]
    (reduce (fn [{:keys [datoms tempid->guid]} {:keys [e a v added]}]
              (let [ref? (dsu/ref-attr? db a)]
                {:datoms (conj datoms (ds/datom (eid->tempid e)
                                                (d/ident db a)
                                                (if ref?
                                                  (eid->tempid v)
                                                  v)
                                                1 ;; this'll be ignored
                                                added))
                 :tempid->guid (cond-> tempid->guid
                                 true
                                 (assoc (eid->tempid e) (e->guid e))

                                 ref?
                                 (assoc (eid->tempid v) (e->guid v)))}))
            {:datoms []
             :tempid->guid {}}
            filtered-datoms)))

;; TODO: needs a whitelist to make sure that clients don't transact
;;       things they shouldn't, e.g. create new attrs.
(defn convert-tx-report
  "Converts tx-report into a format that the client can put into its datascript db"
  [tx-report]
  (def myreport tx-report)
  (let [tx-entity (tx-ent tx-report)]
    (let [e->guid (make-guid-map tx-report)
          grouped-datoms (group-datoms-by-cant-broadcast (:tx-data tx-report) e->guid (:db-before tx-report) (:db-after tx-report) nil)
          filtered-datoms (get grouped-datoms nil)]
      (make-datascript-datoms filtered-datoms (:db-after tx-report) e->guid))))
