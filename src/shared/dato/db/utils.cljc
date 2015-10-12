(ns dato.db.utils
  (:require #?(:clj  [datomic.api :as d]
               :cljs [datascript :as d])))

;; DATASCRIPT

(def ^:dynamic *debug-q* false)

(defn -q [q & args]
  (if *debug-q*
    (let [key (str q)
          start   #?(:clj (.getTime (java.util.Date.))
                          :cljs (.time js/console key))
          res (apply d/q q args)
          end     #?(:clj (.getTime (java.util.Date.))
                          :cljs (.timeEnd js/console key))]
      #?(:clj (println "Q time: " (- end start)))
      res)
    (apply d/q q args)))

(defn q1-by
  "Return single entity id by attribute existence or attribute value"
  ([db attr]
    (->> (-q '[:find ?e :in $ ?a :where [?e ?a]] db attr) ffirst))
  ([db attr value]
    (->> (-q '[:find ?e :in $ ?a ?v :where [?e ?a ?v]] db attr value) ffirst)))

(defn qe
  "If queried entity id, return single entity of first result"
  [q db & sources]
  (->> (apply -q q db sources)
       ffirst
       (d/entity db)))

(defn qes
  "If queried entity ids, return all entities of result"
  [q db & sources]
  (->> (apply -q q db sources)
       (map #(d/entity db (first %)))))

(defn qe-by
  "Return single entity by attribute existence or specific value"
  ([db attr]
    (qe '[:find ?e :in $ ?a :where [?e ?a]] db attr))
  ([db attr value]
    (qe '[:find ?e :in $ ?a ?v :where [?e ?a ?v]] db attr value)))

(defn qe-gl [db guid]
  (qe '[:find ?e :in $ ?a ?v :where [?e ?a ?v]] db :dato/guid guid))

(defn q1-gl [db guid]
  (q1-by db :dato/guid guid))

(defn qes-by
  "Return all entities by attribute existence or specific value"
  ([db attr]
    (qes '[:find ?e :in $ ?a :where [?e ?a]] db attr))
  ([db attr value]
    (qes '[:find ?e :in $ ?a ?v :where [?e ?a ?v]] db attr value)))

(defn val-by
  ([db attr]
   (->> attr
        (d/q '[:find [?v ...] :in $ ?a :where [?e ?a ?v]] db)
        first)))

(defn qmap
  "Convert returned 2-tuples to a map"
  [q & sources]
  (into {} (apply -q q sources)))


(defn touch+
  "By default, touch returns a map that can't be assoc'd. Fix it"
  [ent]
  ;; (into {}) makes the map assoc'able, but lacks a :db/id, which is annoying for later lookups.
  (into (select-keys ent [:db/id]) (d/touch ent)))

(defn t
  "By default, touch returns a map that can't be assoc'd. Fix it"
  [ent]
  ;; (into {}) makes the map assoc'able, but lacks a :db/id, which is annoying for later lookups.
  (into (select-keys ent [:db/id]) (d/touch ent)))

(defn touch-all
  "Runs the query that returns [[eid][eid]] and returns all entity maps.
   Uses the first DB to look up all entities"
  [query & query-args]
  (let [the-db (first query-args)]
    (for [[eid & _] (apply d/q query query-args)]
      (touch+ (d/entity the-db eid)))))

(defn datom-read-api [datom]
  (select-keys datom [:e :a :v :tx :added]))

(defn datom->transaction [datom]
  (let [[a e v tx added?] datom]
    [(if added? :db/add :db/retract)
     e
     #?(:clj  a
        :cljs a)
     v]))

(defn datom->reverse-transaction [datom]
  (let [{:keys [a e v tx added]} datom]
    [(if added :db/retract :db/add) e a v]))

(defn reverse-transaction [transaction conn]
  (let [datoms (:tx-data transaction)]
    (#?(:clj d/transact
             :cljs d/transact!)
       conn
       (mapv datom->reverse-transaction datoms)
       {:undo true
        :can-undo? true})))

(defn tx-report->transaction [report]
  (mapv datom->transaction (:tx-data report)))

(defn ref-attr? [db attrid]
  #?(:clj  (= :db.type/ref (:value-type (d/attribute db attrid)))
     :cljs (-> (qe-by db :db/ident attrid)
               :db/valueType
               (= :db.type/ref))))
