(ns ^:figwheel-always dato.lib.db
    (:require [clojure.set :as sets]
              [datascript :as d]
              [dato.datascript-utils :as dsu]
              [dato.client.utils.seq :refer [dissoc-in]]
              [om.core :as om])
    (:import [goog.ui IdGenerator]))

(defonce listeners (atom {}))

(defn ^:export inspect-listeners []
  (clj->js @listeners))

;; Things that aren't reified in the persistent/ephemeral datomic
;; instance
(def cs-schema
  {:local/current-session {:db/valueType :db.type/ref}
   :session/user          {:db/valueType :db.type/ref}
   :summon/session        {:db/valueType :db.type/ref}})

(defn enum [db db-id]
  ;; Try to support both straight db-id and {:db/id db-id}.
  ;; Messy, but seems to be a common mistake
  (when-let [eid (get db-id :db/id db-id)]
    (:db/ident (d/entity db eid))))

(defn enum-id [db enum]
  (dsu/q1-by db :db/ident enum))

;; Could probably speed this up with a few different conditionals
;; (compare db/ids directly if they're both number/map)
(defn enum= [db a b]
  (let [a (if (keyword? a) a (if (number? a)
                               (enum db a)
                               (enum db (:db/id a))))
        b (if (keyword? b) b (if (number? b)
                               (enum db b)
                               (enum db (:db/id b))))]
    (= a b)))

;; TODO: Listeners should be a function of dato
(defn invalidate-all-listeners! [dato]
  (let [db    (:conn dato)
        eids  (keys (get-in @listeners [db :entity-listeners]))
        attrs (keys (get-in @listeners [db :attribute-listeners]))]
    (doseq [eid eids
            [k callback] (get-in @listeners [db :entity-listeners])]
      (callback {}))
    (doseq [attr attrs
            [k callback] (get-in @listeners [db :attribute-listeners])]
      (println k)
      (callback {}))))

(defn handle-callbacks [db tx-report]
  (let [[eids attrs] (reduce (fn [[eids attrs] datom]
                               [(conj eids (:e datom))
                                (conj attrs (:a datom))])
                             [#{} #{}] (:tx-data tx-report))]
    (doseq [eid eids
            [k callback] (get-in @listeners [db :entity-listeners (str eid)])]
      (callback tx-report))
    (doseq [attr attrs
            [k callback] (get-in @listeners [db :attribute-listeners attr])]
      (callback tx-report))))

(defn setup-listener! [db key]
  (d/listen!
   db
   key
   (fn [tx-report]
     (js/setTimeout
      #(handle-callbacks db tx-report) 10))))

(defn add-entity-listener [conn eid key callback]
  (swap! listeners assoc-in [conn :entity-listeners (str eid) key] callback))

(defn add-attribute-listener [conn attribute key callback]
  (swap! listeners assoc-in [conn :attribute-listeners attribute key] callback))

(defn remove-entity-listener [conn eid key]
  (swap! listeners dissoc-in [conn :entity-listeners (str eid) key]))

(defn remove-attribute-listener [conn attribute key]
  (swap! listeners dissoc-in [conn :attribute-listeners attribute key]))

(defn add-unmounting-entity-listener [owner conn eid callback]
  (om/update-state-nr! owner (fn [s]
                               (update-in s [::listener-key] #(or % (.getNextUniqueId (.getInstance IdGenerator))))))
  (let [key (om/get-state owner ::listener-key)]
    (add-entity-listener conn eid key (fn [tx-report]
                                        (if (om/mounted? owner)
                                          (callback tx-report)
                                          (remove-entity-listener conn eid key))))))

(defn add-unmounting-attribute-listener [owner conn attribute callback]
  (om/update-state-nr! owner (fn [s]
                               (update-in s [::listener-key] #(or % (.getNextUniqueId (.getInstance IdGenerator))))))
  (let [key (om/get-state owner ::listener-key)]
    (add-attribute-listener conn attribute key (fn [tx-report]
                                                 (if (om/mounted? owner)
                                                   (callback tx-report)
                                                   (remove-attribute-listener conn attribute key))))))

(defn refresh-on! [owner conn listen-kvs]
  (doseq [listen-trigger listen-kvs]
    (let [[trigger-type trigger] listen-trigger
          add-trigger-fn         (case trigger-type
                                   :attr add-attribute-listener
                                   :eid  add-entity-listener)
          remove-trigger-fn      (case trigger-type
                                   :attr remove-attribute-listener
                                   :eid  remove-entity-listener)]
      (om/update-state-nr! owner
                           (fn [s]
                             (update-in s [::listener-keys listen-trigger]
                                        #(or % (.getNextUniqueId (.getInstance IdGenerator))))))
      (let [key (om/get-state owner [::listener-keys listen-trigger])]
        (add-trigger-fn conn trigger key (fn [tx-report]
                                           (if (om/mounted? owner)
                                             (om/refresh! owner)
                                             (remove-trigger-fn conn trigger key))))))))

(defn datom->tx [datom fids]
  (let [[e a v d added?] datom]
    [(if added?
       :db/add
       :db/retract)
     ;; we know that eid will be unique to each entity. Pass it through
     ;; d/tempid in case future DS behavior changes
     (d/tempid :db.part/user e)
     a
     (get fids v v)]))

;; fid -> ds db/id
(def all-fids
  (atom {}))

(def pending-tx-info
  (atom {}))

(def pending-datoms
  (atom #{}))

(defn fast-fid [db e]
  (:v (first (d/datoms db :eavt e :dato/guid))))

(defn find-fid [tx-report e]
  (or (fast-fid (:db-after tx-report) e)
      (fast-fid (:db-before tx-report) e)))

(defn add-entity-fid [fid-map tx-report e]
  (if (contains? fid-map e)
    fid-map
    ;; TODO: write retractEntity fn that preserves fid
    (assoc fid-map e (find-fid tx-report e))))

(defn add-ref-fid [fid-map tx-report a v]
  (if (= :db.type/ref (get-in tx-report [:db-after :schema a :db/valueType]))
    (if (contains? fid-map v)
      fid-map
      (assoc fid-map v (find-fid tx-report v)))
    fid-map))

(defn fid-map [tx-report]
  (reduce (fn [acc {:keys [e a v]}]
            (-> acc
              (add-entity-fid tx-report e)
              (add-ref-fid tx-report a v)))
          {} (:tx-data tx-report)))

(defn prep-broadcastable-tx-report [tx-report]
  (let [fids    (fid-map tx-report)
        tx-guid (d/squuid)]
    (assert (every? identity (vals fids)) (str "Missing guid value in broadcast tx-report: " (pr-str fids) " - did you forget to add a :dato/guid to a new entity?"))
    (def l-tx-report tx-report)
    ;; We're broadcasting this, so we need to track the fids for the
    ;; lifetime of this db
    (def tx-fids fids)
    (swap! all-fids merge (sets/map-invert fids))
    (swap! pending-tx-info assoc tx-guid tx-report)
    (swap! pending-datoms (fn [datoms] (apply conj datoms (mapv datom->tx (:tx-data tx-report)))))
    {:db/current-tx (get-in tx-report [:tempids :db/current-tx])
     :tx-data       (:tx-data tx-report)
     :fids          fids
     :tx/guid       tx-guid
     :tx-meta       (dissoc (:tx-meta tx-report) :tx/cb)}))

(defn consume-foreign-tx! [conn preprocessed-tx-report]
  ;; The two-phase commit is to work around
  ;; https://github.com/tonsky/datascript/issues/76 and
  ;; https://github.com/tonsky/datascript/issues/99 - once they're
  ;; fixed we should be able to just to 1 total transaction.
  (def f-tx-0 preprocessed-tx-report)
  (let [tx-guid                (get-in preprocessed-tx-report [:tx/guid])
        grouped                (group-by #(= :dato/guid (second %)) (:datoms preprocessed-tx-report))
        tx-1                   (->> (get grouped true)
                                    (map #(datom->tx % (:guids preprocessed-tx-report)))
                                    (mapv (fn [tx-datom]
                                            (let [guid (nth tx-datom 3)
                                                  local-id (or (dsu/q1-by @conn :dato/guid guid)
                                                               (nth tx-datom 1))
                                                  result (assoc tx-datom 1 local-id)]
                                              (def f-lid local-id)
                                              (def f-guid guid)
                                              (def f-tx-datom result)
                                              result))))
        _                      (def f-tx-1 tx-1)
        intermediate-tx-report (when (seq tx-1)
                                 (d/transact! conn tx-1))
        guid-map               (reduce (fn [run [k v]]
                                         (let [eid (dsu/q1-by @conn :dato/guid v)]
                                           (assoc run k eid))) {} (:guids preprocessed-tx-report))
        tx-2                   (->> (for [[e a v t added?] (get grouped false)]
                                      (datom->tx [(get-in guid-map [e] e) a (get-in guid-map [v] v) t added?] guid-map))
                                    (filter second)
                                    ;; Remove entities that we've already removed locally
                                    (remove #(nil? (nth % 3))))]
    (def f-tx-2 tx-2)
    (when (seq tx-2)
      (swap! pending-datoms (fn [datoms] (apply disj datoms tx-2)))
      (d/transact! conn tx-2))
    (when-let [cb (get-in @pending-tx-info [tx-guid :tx-meta :tx/cb])]
      
      (swap! pending-tx-info dissoc tx-guid)
      (cb @conn))))

