(ns dato.lib.controller)

(defmulti transition
  (fn [db payload] (:event payload)))

(defmethod transition :default
  [db payload]
  (js/console.log "Unhandled transition: " (:event payload)))

(defmethod transition nil
  [db payload]
  (throw (js/Error. (str "Cannot cast! a nil message: " (pr-str payload)))))

(defmulti effect!
  (fn [context old-db new-db payload] (:event payload)))

(defmethod effect! :default
  [context old-db new-db args]
  (js/console.log "Unhandled effect!: " (pr-str (:event args))))

(defmethod transition :db/updated
  [db {:keys [data] :as payload}]
  (assert (keyword? (:intent data)) "DB update must include an :intent key with a keyword value")
  (assert (vector? (:tx data)) "DB update must include an :tx key with a vector value of valid Datascript transaction data")
  (let [m (meta (:tx data))]
    (with-meta (:tx data) (assoc m :tx/intent (:intent data)))))
