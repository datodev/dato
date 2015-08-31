(ns dato.lib.incoming
  (:require [datomic.api :as d]
            [dato.lib.datomic :as datod]
            [dato.datascript-utils :as dsu]))

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


(def attr-whitelist #{:entity/type})

(def edit-whitelist #{})

(defn ds-id->tempid [ds-id]
  (d/tempid :db.part/user (- ds-id)))

(defn whitelisted? [datom]
  (contains? attr-whitelist (:a datom)))

(defn have-guid? [datom guids]
  (instance? java.util.UUID (get guids (:e datom))))

(defn find-fid-datom [db fid]
  (first (d/datoms db :avet :fid fid)))

(defn owns? [db cust owned-id]
  (= (:db/id cust) (:e (first (d/datoms db :vaet owned-id :cust/owned)))))

(defn cant-save-reason [datom guids db cust]
  (cond
    (and (dsu/ref-attr? db (:a datom))
         (not (have-guid? datom guids))) :missing-guid
    :else nil))

(defn group-datoms-by-cant-save [datoms fids db cust]
  (group-by (fn [d] (cant-save-reason d fids db cust)) datoms))

(defn datom->tx [db datom fids]
  (let [ref-attr?     (dsu/ref-attr? db (:a datom))
        local-eid     (dsu/q1-gl db (get fids (:e datom)))
        local-ref-eid (when ref-attr? (dsu/q1-gl db (get fids (:v datom))))]
    [(if (:added datom)
       :db/add
       :db/retract)
     ;; we know that -e will be unique to each entity
     ;;
     ;; Use the local id via the guid lookup if it's an existing
     ;; entity, otherwise it's a new entity.
     (or local-eid (ds-id->tempid (:e datom)))
     (:a datom)
     (if (and ref-attr? (:added datom))
       (ds-id->tempid (:v datom))
       (or local-ref-eid (:v datom)))]))

(defn owned-txes
  "Placeholder method for now"
  [db cust fids]
  (reduce (fn [txes [ds-id fid]]
            txes) [] fids))

(defn fid-txes [fids]
  (map (fn [[ds-id fid]]
         [:db/add (ds-id->tempid ds-id) :dato/guid fid])
       fids))

(defn schema-errors [db txes]
  (let [{:keys [tempids db-after]} (d/with db txes)]
    (reduce (fn [errors [tempid id]]
              (let [ent (d/entity db-after id)]
                ;; TODO: Bring in Schema
                ;;(schemas/check-entity ent)
                (if-let [schema-errors nil]
                  (assoc errors ent schema-errors)
                  errors)))
            {} tempids)))

(defn create-txes [filtered-datoms fids db cust]
  (concat (map (fn [d] (datom->tx db d fids)) filtered-datoms)
          (owned-txes db cust fids)
          (fid-txes fids)))

(defn handle-transaction [conn meta datoms fids db cust]
  (def l-stuff {:meta   meta
                :datoms datoms
                :fids   fids
                :db     db
                :cust   cust})
  (def l-meta meta)
  (let [tx-guid        (:tx/guid meta)
        grouped-datoms (group-datoms-by-cant-save datoms fids db cust)
        passing-datoms (get grouped-datoms nil)
        bad-datoms     (dissoc grouped-datoms nil)
        all-txes       (when (seq passing-datoms)
                         (create-txes passing-datoms fids db cust))
        ;; Prevent the :dato/guid from being retracted
        txes           (remove #(and (= :db/retract (first %))
                                     (= :dato/guid (nth % 2))) all-txes)
        ;; Handle a bug where :dato/guid is nil (this should be fixed
        ;; higher-up, but fixing it here for now
        txes           (remove #(and (= :dato/guid (nth % 2))
                                     (nil? (nth % 3))) txes)
        txes (when (seq txes)
               (conj txes {:db/id   (d/tempid :db.part/tx)
                           :tx/guid tx-guid}))]
    (def lfids fids)
    (def mytxes txes)
    (cond ;; (seq (schema-errors db txes))
          ;; {:rejected-datoms (assoc bad-datoms :schema-errors filtered-datoms)}
          (seq txes)
          (do @(d/transact conn txes)
              {:rejected-datoms bad-datoms})

          :else {:rejected-datoms bad-datoms})))

;; Outgoing
(defn fast-fid [db e]
  (:v (first (d/datoms db :eavt e :dato/guid))))

(defn outgoing-guid-map [tx-report]
  (into {} (filter second (reduce (fn [acc {:keys [e]}]
                                    (if (contains? acc e)
                                      acc
                                      ;; TODO: write retractEntity fn that preserves guid
                                      ;; Negate to turn it into a temp-id
                                      (assoc acc (- e) (or (fast-fid (:db-after tx-report) e)
                                                           (fast-fid (:db-before tx-report) e)))))
                                  {} (:tx-data tx-report)))))


(def black-listed-attr-ns?
  #{"db"})

(defn outgoing-tx-report [tx-report]
  (def outgoing-tx tx-report)
  (let [tx-guid             (when-let [datom (first (filter #(= (d/ident (:db-after tx-report) (:a %)) :tx/guid) (:tx-data outgoing-tx)))]
                              (:v datom))
        guid-map            (outgoing-guid-map tx-report)
        datoms              (for [{:keys [e a v tx added]} (:tx-data tx-report)]
                              [e a v tx added])
        counter             (atom 0)
        [tx-guids _ datoms] (reduce (fn [[guids eid->guids datoms] datom]
                                      (if (black-listed-attr-ns? (namespace (d/ident (:db-after tx-report) (second datom))))
                                        [guids eid->guids datoms]
                                        (let [[e a v tx added?] datom
                                              attr-name         (d/ident (:db-after tx-report) a)
                                              ref-type?         (dsu/ref-attr? (:db-after tx-report) attr-name)
                                              guid              (or (:dato/guid (d/entity (:db-before tx-report) v))
                                                                    (:dato/guid (d/entity (:db-after tx-report) v)))
                                              ref-temp-id       (when ref-type?
                                                                  (or (get eid->guids guid)
                                                                      (swap! counter dec)))
                                              value             (if ref-type?
                                                                  ref-temp-id
                                                                  v)
                                              new-guids         (if ref-type?
                                                                  (assoc guids ref-temp-id guid)
                                                                  guids)
                                              new-eid->guids    (if ref-type?
                                                                  (assoc eid->guids guid ref-temp-id)
                                                                  eid->guids)]
                                          [new-guids new-eid->guids (conj datoms [(- e) attr-name value tx added?])])))
                                    [guid-map {} []] datoms)]
    {:datoms  datoms
     :guids   tx-guids
     :tx/guid tx-guid}))
