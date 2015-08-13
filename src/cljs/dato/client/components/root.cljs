 (ns dato.client.components.root
   (:require [clojure.string :as string]
             [datascript :as d]
             [dato.client.components.utils :refer [kill!]]
             [dato.datascript-utils :as dsu]
             [dato.lib.controller :as con]
             [dato.lib.core :as dato]
             [dato.lib.db :as db]
             [dato.client.utils :as utils]
             [om.core :as om :include-macros true]
             [om-tools.core :refer-macros [defcomponent]]
             [sablono.core :as html :refer-macros [html]]))

(defn delay-focus!
  "Waits 20ms (enough time to queue up a rerender usually, but
  racey) and then focus an input"
  ([root selector]
   (delay-focus! root selector false))
  ([root selector select?]
   (js/setTimeout #(let [input (utils/sel1 root selector)]
                     (.focus input)
                     (when select?
                       (.select input))) 20)))

;; Useful to show what's being rerendered (everything right now for
;; the PoC demo)
(defn rand-color []
  (str "#" (string/join (repeatedly 6 #(rand-nth [1 2 3 4 5 6 7 8 9 0 "A" "B" "C" "D" "E" "F"])))))

(defmethod con/transition :server/find-tasks-succeeded
  [db {:keys [data] :as args}]
  ;; The pull request is insertable as-is, so we don't need to do any
  ;; pre-processing, just return the results.
  (:results data))

(defcomponent root-com [data owner opts]
  (display-name [_]
    "TodoMVC")
  (did-mount [_]
    (d/listen! (dato/conn (om/get-shared owner :dato)) :dato-root #(om/refresh! owner)))
  (will-unmount [_]
    (d/unlisten! (dato/conn (om/get-shared owner :dato)) :dato-root))
  (render [_]
    (html
     (let [{:keys [dato]}   (om/get-shared owner)
           db               (dato/db dato)
           transact!        (partial dato/transact! dato)
           me               (dato/me db)
           session          (dato/local-session db)
           task-filter      (:session/task-filter session)
           pred             (case task-filter
                              :completed :task/completed?
                              :active    (complement :task/completed?)
                              (constantly true))
           all-tasks        (dsu/qes-by db :task/title)
           grouped          (group-by :task/completed? all-tasks)
           active-tasks     (get grouped false)
           completed-tasks  (get grouped true)
           shown-tasks      (->> all-tasks
                                 (filter pred)
                                 (sort-by :task/order))]
       [:div
        [:section.todoapp
         [:header.header
          [:h1 "Todos"]
          [:input.new-todo {:placeholder "What needs to be done?"
                            :autofocus   true
                            :value       (om/get-state owner :new-task-title)
                            :on-change   #(om/set-state! owner :new-task-title (.. % -target -value))
                            :on-key-down (fn [event]
                                           (when (= 13 (.-which event))
                                             (let [task {:db/id           (d/tempid :db.part/user)
                                                         :dato/guid       (d/squuid)
                                                         :task/title      (om/get-state owner :new-task-title)
                                                         :task/completed? false
                                                         :task/order      (count all-tasks)}]
                                               (transact! :task-created [task] {:tx/persist? true})
                                               (om/set-state! owner :new-task-title nil))))}]]
         [:section.main
          (when (first all-tasks)
            [:input.toggle-all {:type      "checkbox"
                                :on-change (fn [event]
                                             (let [checked? (.. event -target -checked)]
                                               (transact! (keyword (str "tasks-all-marked-" (if checked? "complete" "incomplete")))
                                                          (vec (for [task all-tasks]
                                                                 [:db/add (:db/id task) :task/completed? checked?])) {:tx/persist? true})))
                                :checked   (not (boolean (first active-tasks)))}])
          [:label {:for "toggle-all"} "Mark all as complete"]
          (into
           [:ul.todo-list]
           (for [task shown-tasks]
             ^{:key (str "task-item-" (:db/id task))}
             [:li {:key             (str "task-item-" (:db/id task))
                   :class           (cond
                                      (:task/completed? task)                               "completed"
                                      (= (om/get-state owner [:editing :id]) (:db/id task)) "editing")
                   :on-double-click (fn [event]
                                      (om/set-state! owner :editing {:id       (:db/id task)
                                                                     :original (:task/title task)})
                                      (delay-focus! (om/get-node owner) (str ".task-" (:db/id task)) true))}
              [:div.view
               [:input.toggle {:type      "checkbox"
                               :checked   (:task/completed? task)
                               :on-change (fn [event]
                                            (transact! :task-toggled [{:db/id           (:db/id task)
                                                                       :task/completed? (not (:task/completed? task))}]
                                                       {:tx/persist? true}))}]
               [:label (:task/title task)]
               [:button.destroy {:on-click #(transact! :task-destroyed [[:db.fn/retractEntity (:db/id task)]] {:tx/persist? true})}]]
              [:input.edit {:class       (str "task-" (:db/id task))
                            :value       (:task/title task)
                            :on-key-down #(condp = (.-which %)
                                            13 (om/set-state! owner :editing {})
                                            27 (do
                                                 (transact! :task-title-restored [{:db/id      (:db/id task)
                                                                                   :task/title (om/get-state owner [:editing :original])}] {:tx/persist? true})
                                                 (om/set-state! owner :editing {}))
                                            nil)
                            :on-change   #(transact! :task-title-edited [{:db/id      (:db/id task)
                                                                          :task/title (.. % -target -value)}] {:tx/persist? true})}]]))]
         [:footer.footer
          [:span.todo-count [:strong (count active-tasks)] " items left"] 
          [:ul.filters
           [:li 
            [:a {:href     "#/"
                 :class    (when (or (= :all task-filter) (not task-filter)) "selected")
                 :on-click (fn [event]
                             (kill! event)
                             (transact! :filter-updated [{:db/id               (:db/id session)
                                                          :session/task-filter :all}] {:tx/persist? true}))} "All"]] 
           [:li 
            [:a {:href     "#/active"
                 :class    (when (= :active task-filter) "selected")
                 :on-click (fn [event]
                             (kill! event)
                             (transact! :filter-updated [{:db/id               (:db/id session)
                                                          :session/task-filter :active}] {:tx/persist? true}))} "Active"]] 
           [:li 
            [:a {:href     "#/completed"
                 :class    (when (= :completed task-filter) "selected")
                 :on-click (fn [event]
                             (kill! event)
                             (transact! :filter-updated [{:db/id               (:db/id session)
                                                          :session/task-filter :completed}] {:tx/persist? true}))} "Completed"]]]
          (when (first completed-tasks)
            [:button.clear-completed
             {:on-click #(transact! :completed-tasks-cleared (->> all-tasks
                                                                  (filter :task/completed?)
                                                                  (mapv (fn [task] [:db.fn/retractEntity (:db/id task)]))) {:tx/persist? true})}
             "Clear completed"])]] 
        [:footer.info
         [:p "Double-click to edit a todo"] 
         [:p "Created by " 
          [:a {:href "https://twitter.com/sgrove"} "Sean Grove"]]
         [:p "Part of " 
          [:a {:href "http://todomvc.com"} "TodoMVC"]]]]))))
