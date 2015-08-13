(ns dato.datomic
  (:require [clojure.core.async :as async]
            [clojure.tools.logging :refer (infof)]
            [clojure.walk :as walk]
            [datomic.api :refer [q] :as d]
            [environ.core :as config]
            [dato.datascript-utils :as dsu])
  (:import java.util.UUID))


(def remote-uri
  "datomic:sql://dato?jdbc:postgresql://my-remote-server.com:5432/dato?user=production_dato&password=datolovesyou")

(def local-uri
  (or (config/env :datomic-local-uri)
      "datomic:sql://dato_dev?jdbc:postgresql://127.0.0.1:5432/dato_dev?user=dev_dato&password=datostilllovesyou"))

(def default-uri
  (if (config/env :is-dev)
    local-uri
    remote-uri))

(defn make-conn [& [options]]
  (d/connect (or (:uri options) default-uri)))

(defn conn []
  (make-conn))

(defn ddb []
  (d/db (conn)))

(defn init []
  (infof "Creating default database if it doesn't exist: %s"
         (d/create-database default-uri))
  (infof "Ensuring connection to default database")
  (infof "Connected to: %s" (conn)))
