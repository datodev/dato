(ns dato.datomic.schema
  (:require [dato.db.utils :as dsu]
            [datomic.api :as d :refer [db q]]))

(defn attribute [ident type & {:as opts}]
  (merge {:db/id                 (d/tempid :db.part/db)
          :db/ident              ident
          :db/valueType          type
          :db.install/_attribute :db.part/db}
         {:db/cardinality :db.cardinality/one}
         opts))

(defn function [ident fn & {:as opts}]
  (merge {:db/id    (d/tempid :db.part/user)
          :db/ident ident
          :db/fn    fn}
         opts))

(defn enum [ident]
  {:db/id     (d/tempid :db.part/user)
   :db/ident  ident
   :dato/guid (d/squuid)})

(def schema-1
   ;; Universal attributes
  [(attribute :migration/version :db.type/long)
   (attribute :dato/guid :db.type/uuid :db/unique :db.unique/identity)
   (attribute :tx/guid :db.type/uuid :db/unique :db.unique/identity)
   (attribute :tx/session-id :db.type/string)])

(def schema-2
  (vec
   (concat
    ;; Users
    [(attribute :user/id          :db.type/uuid :db/unique :db.unique/identity)
     (attribute :user/email       :db.type/string :db/unique :db.unique/identity)
     (attribute :user/given-name  :db.type/string)
     (attribute :user/family-name :db.type/string)]
    
    ;; Tasks
    [(attribute :task/user  :db.type/ref)
     (attribute :task/title :db.type/string)
     (attribute :task/completed? :db.type/boolean)
     (attribute :task/order :db.type/long)])))

(defonce schema-ents
  (atom nil))

(defn enums []
  (->> @schema-ents
       (filter #(= :db.type/ref (:db/valueType %)))
       (map :db/ident )
       (set)))

(defn get-ident [a]
  (->> @schema-ents
       (filter #(= a (:db/id %)) )
       first 
       :db/ident))

(defn get-schema-ents [db]
  (dsu/touch-all '{:find [?t]
                   :where [[?t :db/ident ?ident]]}
                 db))

(defn ensure-schema
  ([conn]
   (let [res @(d/transact conn schema-1)
         res @(d/transact conn schema-2)
         ents (get-schema-ents (:db-after res))]
     (reset! schema-ents ents)
     res)))

(defn init []
  (ensure-schema))

;; (init)

