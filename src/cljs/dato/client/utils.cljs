(ns dato.client.utils
  (:require [clojure.string :as string]
            [goog.dom.DomHelper :as gdomh]
            [goog.Uri])
  (:import [goog.Uri]))

(defn map-vals
  "Create a new map from m by calling function f on each value to get a new value."
  [m f & args]
  (when m
    (into {}
          (for [[k v] m]
            [k (apply f v args)]))))

(defn map-keys
  "Create a new map from m by calling function f on each key to get a new key."
  [m f & args]
  (when m
    (into {}
          (for [[k v] m]
            [(apply f k args) v]))))

(def ffilter
  (comp first filter))

(defn https-url? [url]
  (= (subs url 0 8) "https://"))

(def parsed-uri
  (goog.Uri. (-> (.-location js/window) (.-href))))

(def initial-query-map
  {}
  #_{:direction               (if (empty? (.getParameterValue parsed-uri "activity-direction"))
                              :all
                              (keyword (.getParameterValue parsed-uri "activity-direction")))
   :weeks                   (or (.getParameterValue parsed-uri "weeks") 12)
   :limit                   (or (.getParameterValue parsed-uri "limit") 30)
   :scale                   (or (.getParameterValue parsed-uri "scale") "daily")
   :account-update-interval (.getParameterValue parsed-uri "update-interval")
   :groups                  (let [groups (string/split (.getParameterValue parsed-uri "scale") #",")]
                              (if (empty? groups) [:marketing :sales :support :other] groups))
   :api-key                 (.getParameterValue parsed-uri "api-key")
   :log-channels?           (or (.getParameterValue parsed-uri "log-channels") false)
   :restore-state?          (= (.getParameterValue parsed-uri "restore-state") "true")})

(defn log [& msg]
  (.apply (.-log js/console) js/console (clj->js msg)))

(defn sel1
  ([query]
   (sel1 js/document query))
  ([node query]
   (.querySelector node (name query))))

(defn destroy-sel!
  ([selector]
   (destroy-sel! js/document selector))
  ([node selector]
   (gdomh/removeNode (sel1 node selector))))

(defn map->query-params [m]
  (reduce (fn [run [k v]]
            (let [lead (if run "&" "")]
              (str run lead (js/encodeURIComponent (name k)) "=" (js/encodeURIComponent v)))) nil
              m))

(defn indices [pred coll]
  (keep-indexed #(when (pred %2) %1) coll))

(defn index-of [pred coll]
  (first (keep-indexed #(when (pred %2) %1) coll)))

(defn sfsubs
  ([string start]
   (subs string start))
  ([string start end]
   (let [cnt (count string)]
     (subs string (min cnt start) (min cnt end)))))

(defn truncate
  ([string end]
   (string/trimr (sfsubs string 0 end)))
  ([string end ellipsis]
   (let [str-len (count string)
         end     (min end (if (< end str-len) (- str-len (count ellipsis)) js/Infinity))
         tmp     (string/trimr (sfsubs string 0 end))]
     (str tmp (when (>= (count string)
                       (+ end (count ellipsis))) ellipsis)))))

(defn handle-mouse-move [cast! event]
  (cast! {:event :ui/mouse-moved
          :data  [(.. event -pageX)
                  (.. event -pageY)]}))

(defn handle-mouse-down [cast! event]
  (cast! {:event :ui/mouse-down
          :data  [(.. event -pageX)
                  (.. event -pageY)]}))

(defn handle-mouse-up [cast! event]
  (cast! {:event :ui/mouse-up
          :data  [(.. event -pageX)
                  (.. event -pageY)]}))
