(ns dato.lib.utils
  (:import [goog.ui IdGenerator]))

(defn dissoc-in
  "Dissociates an entry from a nested associative structure returning a new
  nested structure. keys is a sequence of keys. Any empty maps that result
  will not be present in the new structure."
  [m [k & ks :as keys]]
  (if ks
    (if-let [nextmap (get m k)]
      (let [newmap (dissoc-in nextmap ks)]
        (if (seq newmap)
          (assoc m k newmap)
          (dissoc m k)))
      m)
    (dissoc m k)))

;; TODO: should start dato with some config that turns logging on or off
(defn mlog [& msg]
  (.apply (.-log js/console) js/console (clj->js msg)))

(defn mwarn [& msg]
  (.apply (.-warn js/console) js/console (clj->js msg)))

(defn merror [& msg]
  (.apply (.-error js/console) js/console (clj->js msg)))

(defonce id-generator (.getInstance IdGenerator))

(defn unique-id []
  (.getNextUniqueId id-generator))
