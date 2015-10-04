(ns dato.lib.utils.queue
  (:refer-clojure :exclude [pop!])
  (:require [dato.lib.utils :as utils]))

;; word to the wise: don't put nils in the queue and don't remove things outside of add-consumer
(defn new-queue []
  (atom cljs.core/PersistentQueue.EMPTY))

(defn pop! [queue-atom]
  (loop [queue-val @queue-atom]
    (if (compare-and-set! queue-atom queue-val (pop queue-val))
      (peek queue-val)
      (recur @queue-atom))))

(defn pop-all! [queue-atom]
  (loop [queue-val @queue-atom]
    (if (compare-and-set! queue-atom queue-val (empty queue-val))
      (vec queue-val)
      (recur @queue-atom))))

(defn enqueue! [queue-atom & items]
  (swap! queue-atom (fn [q] (apply conj q items))))

(defn add-consumer
  "Takes an optional consumer id for removing the consumer later. Will return
   consumer id. Handler will be given the queue atom, it's up to the consumer to
   use safe operations (like pop! or pop-all!) to consume the queue."
  ([queue handler]
   (add-consumer queue handler (utils/unique-id)))
  ([queue handler consumer-id]
   (add-watch queue consumer-id (fn [_ _ old new]
                                  (when (> (count new) (count old))
                                    (handler queue))))))
