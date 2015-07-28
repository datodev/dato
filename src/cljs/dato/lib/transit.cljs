(ns dato.lib.transit)

(deftype URIHandler []
    Object
  (tag [this v] "r")
  (rep [this v] (.toString v))
  (stringRep [this v] nil))

(deftype DatomHandler []
  Object
  (tag [_ _] "datascript/Datom")
  (rep [_ d] #js [(.-e d) (.-a d) (.-v d) (.-tx d) (.-added d)])
  (stringRep [_ _] nil))

