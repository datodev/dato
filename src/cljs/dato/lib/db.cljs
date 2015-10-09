(ns ^:figwheel-always dato.lib.db
    (:require [clojure.set :as sets]
              [datascript :as d]
              [dato.db.utils :as dsu]
              [dato.lib.utils :refer [dissoc-in]]
              [om.core :as om])
    (:import [goog.ui IdGenerator]))

(defonce listeners (atom {}))

(defn ^:export inspect-listeners []
  (clj->js @listeners))

;; Things that aren't reified in the persistent/ephemeral datomic
;; instance
(def cs-schema
  {:local/current-session {:db/valueType :db.type/ref}})

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
