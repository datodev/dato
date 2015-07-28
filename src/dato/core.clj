(ns dato.core
  (:require [dato.server :as server])
  (:gen-class))

(defn -main
  [& port]
  (server/run port))
