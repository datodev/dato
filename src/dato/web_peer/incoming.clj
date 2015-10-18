(ns dato.web-peer.incoming
  (:require [clojure.string :as str]
            [datomic.api :as d]
            [dato.db.utils :as dsu]
            [dato.web-peer.outgoing :as web-peer-outgoing]))

(defn ensure-pull-required-fields [pattern]
  (letfn [(ensure-fields [node]
            (as-> node node
              (if (some #{:dato/guid} node)
                node
                (conj node :dato/guid))
              (if (some #{:db/id} node)
                node
                (conj node :db/id))
              (mapv (fn [field]
                      (if (map? field)
                        (handle-map field)
                        field)) node)))
          (handle-map [field]
            (reduce (fn [run k]
                      (update-in run [k] ensure-fields)) field (keys field)))]
    (ensure-fields pattern)))

(defn ds-id->tempid [ds-id]
  (d/tempid :db.part/user (- ds-id)))

(defn have-guid? [[_ e _ _] guids]
  (instance? java.util.UUID (get guids e)))

(defn have-ref-guid? [[_ _ _ v] guids]
  (instance? java.util.UUID (get guids v)))

(defmulti valid-db-fn? (fn [tx] (first tx)))

(defmethod valid-db-fn? :default
  [tx]
  false)

(defmethod valid-db-fn? :db/add
  [tx]
  (= 4 (count tx)))

(defmethod valid-db-fn? :db/retract
  [tx]
  (= 4 (count tx)))

(defmethod valid-db-fn? :db.fn/retractEntity
  [tx]
  (= 2 (count tx)))

(defn tx-type [tx]
  (cond (= 4 (count tx))
        :normal
        (= 2 (count tx))
        :db-fn))

(defn cant-save-tx-reason [[db-fn e a v :as tx] guids db cust]
  (let [tx-type (tx-type tx)]
    (cond (not (vector? tx))
          :wrong-format-for-tx

          (not (valid-db-fn? tx))
          :invalid-db-fn

          (not (have-guid? tx guids))
          :missing-guid

          (and (= :normal tx-type)
               (dsu/ref-attr? db a)
               (not (have-ref-guid? tx guids)))
          :missing-guid-for-ref-value

          :else nil)))

(defn group-txes-by-cant-save [txes guids db cust]
  (group-by (fn [tx] (cant-save-tx-reason tx guids db cust)) txes))

(defn guid-txes [guids]
  (map (fn [[ds-id guid]]
         [:db/add (ds-id->tempid ds-id) :dato/guid guid])
       guids))

(defn fill-txes [filtered-txes guids db cust]
  (let [guids-to-use (atom guids)]
    (concat (doall (map (fn [tx]
                          (if (= :db.fn/retractEntity (first tx))
                            (do
                              (swap! guids-to-use dissoc (second tx))
                              [(first tx) [:dato/guid (get guids (second tx))]])
                            (update tx 1 ds-id->tempid)))
                        filtered-txes))
            (guid-txes @guids-to-use))))

(defn handle-txes [conn meta incoming-txes guids db cust]
  (let [grouped-txes (group-txes-by-cant-save incoming-txes guids db cust)
        filtered-txes (get grouped-txes nil)
        bad-txes (dissoc grouped-txes nil)
        txes (fill-txes filtered-txes guids db cust)]
    (def myguids guids)
    (def myincoming incoming-txes)
    (def mygroupedtxes grouped-txes)
    (def mytxtxes filtered-txes)
    (def mytxes txes)
    (def mymeta meta)
    (def myconn conn)
    (if (seq filtered-txes)
      (let [report @(d/transact conn (conj txes (merge {:db/id (d/tempid :db.part/tx)}
                                                       ;; Do we merge meta here?
                                                       meta)))]
        (def myreport report)
        (merge (web-peer-outgoing/convert-tx-report report)
               {:rejected-txes bad-txes}))
      {:rejected-txes bad-txes})))
