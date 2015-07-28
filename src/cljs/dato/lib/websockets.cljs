(ns dato.lib.websockets)

(def event->name
  {:on-open    "onopen"
   :on-message "onmessage"
   :on-close   "onclose"
   :on-error   "onerror"})

(defn build [host path handlers]
  {:pre [(every? #{:on-open :on-message :on-close :on-error} (keys handlers))]}
  (let [uri (str "ws://" host path)
        ws  (js/WebSocket. uri)]
    (doseq [[event handler] handlers]
      (aset ws (event->name event) handler))
    ws))
