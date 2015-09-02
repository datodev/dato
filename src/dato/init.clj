(ns dato.init
  (:require dato.datomic
            dato.datomic.migrations
            dato.datomic.schema
            dato.nrepl
            dato.server)
  (:import [java.util Date]))

(defn init []
  (dato.nrepl/init)
  (dato.datomic/init)
  (dato.datomic.schema/init)
  (dato.datomic.migrations/init)
  (dato.server/init))

(defn -main []
  (println "Initializing dato at" (Date.))
  (init)
  (println "Finished initializing dato at" (Date.)))
