(ns dato.nrepl
  (:require [clojure.tools.nrepl.server :refer (start-server)]
            [cider.nrepl]
            [environ.core :as config]))

(defn port []
  (when (config/env :nrepl-port)
    (Integer/parseInt (config/env :nrepl-port))))

(defn init []
  (if-let [port (port)]
    (do
      (println "Starting nrepl on port" port)
      (start-server :port port :handler cider.nrepl/cider-nrepl-handler))
    (println "Not starting nrepl, export NREPL_PORT to start an embedded nrepl server")))
