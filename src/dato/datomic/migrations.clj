(ns dato.datomic.migrations
  (:require [clojure.tools.logging :as log]
            [datomic.api :as d :refer [db q]]
            [slingshot.slingshot :refer (try+ throw+)])
  (:import java.util.UUID))

(defn migration-entity
  "Finds the entity that keeps track of the migration version, there should
   be only one of these."
  [db]
  (d/entity db (d/q '[:find ?t .
                      :where [?t :migration/version]]
                    db)))

(defn migration-version
  "Gets the migration version, or returns -1 if no migrations have run."
  [db]
  (:migration/version (migration-entity db) -1))

(defn update-migration-version
  "Updates the migration version, throwing an exception if the version does not increase
   the previous version by 1."
  [conn version]
  (let [e (migration-entity (db conn))]
    @(d/transact conn [[:db.fn/cas (:db/id e) :migration/version (dec version) version]])))

(defn add-migrations-entity
  "First migration, adds the entity that keep track of the migration version."
  [conn]
  (assert (= -1 (migration-version (db conn))))
  ;; This will set it to -1, then update-migration-version will set it to 0
  @(d/transact conn [{:db/id (d/tempid :db.part/user) :migration/version -1}]))

(defn create-dummy-users
  "Creates a few dummy users for testing"
  [conn]
  @(d/transact conn [{:db/id (d/tempid :db.part/user)
                      :user/id (d/squuid)
                      :user/email "dwwoelfel@gmail.com"
                      :user/given-name "Daniel"
                      :user/family-name "Woelfel"}
                     {:db/id (d/tempid :db.part/user)
                      :user/id (d/squuid)
                      :user/email "sean@example.com"
                      :user/given-name "Sean"
                      :user/family-name "Grove"}]))

(def migrations
  "Array-map of migrations, the migration version is the key in the map.
   Use an array-map to make it easier to resolve merge conflicts."
  [[0 #'add-migrations-entity]
   [1 #'create-dummy-users]
   ])

(defn necessary-migrations
  "Returns tuples of migrations that need to be run, e.g. [[0 #'migration-one]]"
  [conn]
  (drop (inc (migration-version (db conn))) migrations))

(defn run-necessary-migrations [conn]
  (doseq [[version migration] (necessary-migrations conn)]
    (log/infof "migrating datomic db to version %s with %s" version migration)
    (migration conn)
    (update-migration-version conn version)))

(defn init [conn]
  (run-necessary-migrations conn))
