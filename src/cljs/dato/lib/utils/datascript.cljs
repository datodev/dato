(ns dato.lib.utils.datascript
  (:require [dato.lib.utils :as utils]
            [datascript.core :as dc])
  (:require-macros [datascript.core :refer (raise)]))

(defn explode-entity [db entity]
  (let [eid (:db/id entity)]
    (for [[a vs] entity
          :when  (not= a :db/id)
          :let   [reverse?   (dc/reverse-ref? a)
                  straight-a (if reverse? (dc/reverse-ref a) a)]
          v      (dc/maybe-wrap-multival db a vs)]
      (if (and (dc/ref? db straight-a) (map? v)) ;; another entity specified as nested map
        (assoc v (dc/reverse-ref a) eid)
        (if reverse?
          [:db/add v   straight-a eid]
          [:db/add eid straight-a v])))))

(defn maybe-replace-temp-id [{:keys [ids genid->tempid tx]}]
  (if (number? (second tx))
    {:ids ids
     :tx tx
     :genid->tempid genid->tempid}
    (if-let [tempid (genid->tempid (second tx))]
      {:ids ids
       :tx (assoc-in tx [1] tempid)
       :genid->tempid genid->tempid}
      (let [tempid (dec (apply min 0 ids))]
        {:ids (conj ids tempid)
         :tx (assoc-in tx [1] tempid)
         :genid->tempid (assoc genid->tempid (second tx) tempid)}))))

(defn maybe-replace-temp-value [{:keys [ids genid->tempid tx]}]
  (let [[_ _ _ v] tx]
    (if-not (object? v)
      {:ids ids
       :tx tx
       :genid->tempid genid->tempid}
      (if-let [tempid (genid->tempid v)]
        {:ids ids
         :tx (assoc-in tx [3] tempid)
         :genid->tempid genid->tempid}
        (let [tempid (dec (apply min 0 ids))]
          {:ids (conj ids tempid)
           :tx (assoc-in tx [3] tempid)
           :genid->tempid (assoc genid->tempid (nth tx 3) tempid)})))))

(defn insert-tempids [txes]
  (let [all-ids (reduce (fn [acc tx] (if (number? (second tx))
                                       (conj acc (second tx))
                                       acc))
                        #{} txes)]
    (:txes (reduce (fn [{:keys [ids txes genid->tempid]} tx]
                     (-> {:ids ids :genid->tempid genid->tempid :tx tx}
                       (maybe-replace-temp-id)
                       (maybe-replace-temp-value)
                       (#(assoc % :txes (conj txes (:tx %))))))
                   {:ids all-ids :genid->tempid {} :txes []} txes))))

(defn explode-tx-data
  "Converts a transaction into vector format.

   Example:
   {:db/id 10 :task/title \"hello\" :task/complete? false}
   => [[:db/add 10 :task/title \"hello\"]
       [:db/add 10 :task/complete? false]]"
  ([db es]
   (explode-tx-data db es []))
  ([db es results]
   (when-not (or (nil? es) (sequential? es))
     (raise "Bad transaction data " es ", expected sequential collection"
            {:error :transact/syntax, :tx-data es}))
   (let [[entity & entities] es]
     (cond
       (nil? entity)
       (insert-tempids results)

       (map? entity)
       (let [eid (or (:db/id entity)
                     ;; Just need something unique, replace it with a negative number later
                     (js-obj))
             new-entity  (assoc entity :db/id eid)]
         (recur db
                (concat entities (explode-entity db new-entity))
                results))

       (sequential? entity)
       (let [[op e a v] entity]
         (cond
           (contains? #{:db/add :db/retract :db.fn/retractEntity} op)
           (recur db
                  entities
                  (conj results entity))

           (contains? #{:db.fn/call :db.fn/retractAttribute} op)
           (raise (str "Unsupported web-peer operation " op " (sorry!)")
                  {:error :web-peer/unsupported :op op})

           ;; XXX: what's going on with these two?
           ;; (tx-id? e)
           ;; (recur db
           ;;        entities
           ;;        (conj results entity))

           ;; (and (ref? db a) (tx-id? v))
           ;; (recur report (concat [[op e a (current-tx report)]] entities))

           :else
           (raise "Unknown operation at " entity ", expected :db/add, :db/retract, :db.fn/call, :db.fn/retractAttribute or :db.fn/retractEntity"
                  {:error :transact/syntax, :operation op, :tx-data entity})))
       :else
       (raise "Bad entity type at " entity ", expected map or vector"
              {:error :transact/syntax, :tx-data entity})))))
