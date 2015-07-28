(ns dato.client.components.utils)

(defn kill! [event]
  (doto event
    (.preventDefault)
    (.stopPropagation)))
