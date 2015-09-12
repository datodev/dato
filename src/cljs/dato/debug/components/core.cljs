(ns dato.debug.components.core
  (:require [cljs.js :as cljs]
            [cljsjs.codemirror :as cm]
            [cljsjs.codemirror.mode.clojure :as cm-clj]
            [cljs.pprint :as pp]
            [cljs.reader :as reader]
            [clojure.string :as string]
            [dato.lib.core :as dato]
            [dato.db.utils :as dsu]
            [dato.debug.components.keyboard-listener :as keyboard]
            [goog.dom.classes :as gclass]
            [om-bootstrap.panel :as bs-p]
            [om-bootstrap.grid :as g]
            [om.core :as om :include-macros true]
            [om-tools.dom :as dom])
  (:import [goog.net XhrIo]))

(def default-st
  (cljs/empty-state))

(defn get-file [lib cb]
  (let [{:keys [path macros]} lib]
    (.send XhrIo (str "/_source" "?path=" path (when macros "&macros=true"))
           (fn [e]
             (cb (js/JSON.parse (.. e -target getResponseText)))))))

(defn load [lib cb]
  (js/console.log "lib: " lib)
  (get-file lib
    (fn [file-info]
      (cb {:lang   (keyword (aget file-info "lang"))
           :source (aget file-info "src")}))))

(defn eval
  ([src]
   (eval src (fn [{:keys [error value]}]
               (js/console.log error value))))
  ([src cb]
   (binding []
     (cljs/eval-str default-st src 'dato.debug
                    {:load       load
                     :eval       cljs/js-eval
                     :ns         'dato.debug
                     :source-map true}
                    cb))))

(defn eval-expr
  ([src]
   (eval src (fn [{:keys [error value]}]
               (js/console.log error value))))
  ([src cb]
   (binding []
     (cljs/eval-str default-st src 'dato.debug
                    {:load       load
                     :eval       cljs/js-eval
                     :context    :expr
                     :ns         'dato.debug
                     :source-map true}
                    cb))))

(defn compile-expr
  ([src]
   (eval src (fn [{:keys [error value]}]
               (js/console.log error value))))
  ([src cb]
   (binding []
     (cljs/compile-str default-st src 'dato.debug
                       {:load    load
                        :eval    cljs/js-eval
                        :context :expr
                        :ns      'dato.debug}
                    cb))))

(defn state-watcher-coms [state-watchers]
  (keys (dissoc state-watchers ::count)))

(defn com-owner [state-watchers com-key]
  (get-in state-watchers [com-key :owner]))

(defn com-state [state-watchers com]
  (get-in state-watchers [com :states]))

(defn com-name [state-watchers com]
  (when com
    (get-in state-watchers [com :name])))

(def state-watchers
  (atom {::count 0}))

(defn com-key [com]
  (om/react-id com))

(defn watch-state-did-mount [f]
  (fn []
    (this-as
     this
     (swap! state-watchers
            (fn [s]
              (let [com-id (com-key this)]
                (-> s
                    (update-in [::count] inc)
                    (assoc-in [com-id :name] ((or (aget this "getDisplayName") (fn [] "Unknown"))))
                    (assoc-in [com-id :owner] this)))))
     (.call f this))))

(defn watch-state-did-update [f]
  (fn [prev-props prev-state]
    (this-as
     this
     (swap! state-watchers
            (fn [s]
              (let [com-id (com-key this)]
                (-> s
                    (assoc-in [com-id :states :dato] (dato/get-state this))
                    (assoc-in [com-id :states :om] (om/get-state this))))))
     (.call f this prev-props prev-state))))

(defn watch-state-will-unmount [f]
  (fn []
    (this-as
     this
     (swap! state-watchers
            (fn [s]
              (let [com-id (com-key this)]
                (-> s
                    (dissoc com-id)))))
     (.call f this))))

(defn wrap-watch-state-methods [methods]
  (-> methods
      (update-in [:componentDidMount] watch-state-did-mount)
      (update-in [:componentDidUpdate] watch-state-did-update)
      (update-in [:componentWillUnmount] watch-state-will-unmount)))

(def watch-state-methods
  (om/specify-state-methods!
   (-> om/pure-methods
       (wrap-watch-state-methods)
       (clj->js))))

(defn highlight-node! [node]
  (gclass/add node "dato-inspected"))

(defn unhighlight-node! [node]
  (gclass/remove node "dato-inspected"))

(defn history-com [data owner opts]
  (reify
    om/IDisplayName
    (display-name [_]
      "DatoDebuggerContainer")
    om/IWillMount
    (will-mount [_]
      (om/set-state! owner [:current-save-slot] 0)
      (om/set-state! owner [:tool] :query)
      (om/set-state! owner [:current-query] "default")
      (om/set-state! owner [:open?] (or (:open? opts) false))
      (om/set-state! owner [:query] (or (:expressions opts) [])))
    om/IDidUpdate
    (did-update [_ _ prev]
      (let [node                (om/get-node owner)
            editor-node         (.querySelector node ".editor")
            switched-to-editor? (and (not= :debugger (:tool prev))
                                     (= :debugger (om/get-state owner [:tool])))]
        (when (and switched-to-editor? editor-node)
          (js/CodeMirror editor-node #js{:value "(code)"
                                         :mode  "clojure"
                                         :extraKeys #js{:Alt-Enter (fn [cm]
                                                                     (let [doc       (.getDoc cm)
                                                                           doc-value (.getValue doc)]
                                                                       (js/console.log (.greet js/window "Life"))
                                                                       (eval doc-value)
                                                                       (js/console.log (.greet js/window "Life"))
                                                                       (js/console.log "--------------------")))}}))))
    om/IRender
    (render [_]
      (let [log                 (:log data)
            log-count           (count log)
            dato                (om/get-shared owner [:dato])
            db                  (dato/db dato)
            app-root            (om/value (om/get-shared owner [:app-root]))
            selected-state-idx  (max 0 (min (or (om/get-state owner [:states :selected]) 0) log-count))
            selected-state      (when (pos? log-count)
                                  (nth log selected-state-idx))
            set-app-state!      (fn [idx]
                                  (when-let [state (and (pos? log-count)
                                                        (get-in log [idx :tx :db-after]))]
                                    (let [new-db (om/value state)
                                          conn   (dato/conn dato)]
                                      (reset! conn new-db)
                                      (om/refresh! app-root))))
            save-state!         (fn [_ slot-idx]
                                  (let [local-storage-name (str "dato_save_state_" slot-idx)
                                        existing-db-str    (js/localStorage.getItem local-storage-name)
                                        existing-db        (when existing-db-str
                                                             (reader/read-string existing-db-str))
                                        title              (or (:title existing-db)
                                                               (js/prompt "Title for this save state" (str "State " slot-idx)))
                                        title              (if (string/blank? title)
                                                             (str "State " slot-idx)
                                                             title)]
                                    (js/localStorage.setItem local-storage-name (pr-str {:title title
                                                                                         :db    (dato/db dato)}))))
            restore-state! (fn [new-db]
                             (let [conn (dato/conn dato)]
                               (reset! conn new-db)
                               (om/refresh! app-root)))
            restore-save-state! (fn [_ slot-idx]
                                  (let [local-storage-name (str "dato_save_state_" slot-idx)
                                        existing-db-str    (js/localStorage.getItem local-storage-name)
                                        existing-db        (when existing-db-str
                                                             (reader/read-string existing-db-str))]
                                    (when existing-db
                                      (let [new-db (:db existing-db)
                                            conn   (dato/conn dato)]
                                        (reset! conn new-db)
                                        (om/refresh! app-root)))))
            play-all-states!    (fn play-all-states!
                                  ([]
                                   (play-all-states! 0))
                                  ([idx]
                                   (cond
                                     (> idx (dec log-count))          nil
                                     (get-in log [idx :tx :db-after]) (do
                                                                        (set-app-state! idx)
                                                                        (js/setTimeout #(play-all-states! (inc idx)) 1))
                                     :else                            (js/setTimeout #(play-all-states! (inc idx)) 1))))]
        (dom/div
         {:style {:display (when-not (om/get-state owner [:open?]) "none")}}
         (om/build keyboard/keyboard-handler {} {:opts {:keymap (atom {["ctrl+slash"] #(om/update-state! owner [:open?] not)
                                                                       ["ctrl+s"]     #(save-state! owner (om/get-state owner [:current-save-slot]))
                                                                       ["ctrl+r"]     #(restore-save-state! owner (om/get-state owner [:current-save-slot]))
                                                                       ["ctrl+0"]     #(do
                                                                                         (restore-save-state! owner 0)
                                                                                         (om/set-state! owner [:current-save-slot] 0))
                                                                       ["ctrl+1"]     #(do
                                                                                         (restore-save-state! owner 1)
                                                                                         (om/set-state! owner [:current-save-slot] 1))
                                                                       ["ctrl+2"]     #(do
                                                                                         (restore-save-state! owner 2)
                                                                                         (om/set-state! owner [:current-save-slot] 2))
                                                                       ["ctrl+3"]     #(do
                                                                                         (restore-save-state! owner 3)
                                                                                         (om/set-state! owner [:current-save-slot] 3))
                                                                       ["ctrl+4"]     #(do
                                                                                         (restore-save-state! owner 4)
                                                                                         (om/set-state! owner [:current-save-slot] 4))
                                                                       ["ctrl+5"]     #(do
                                                                                         (restore-save-state! owner 5)
                                                                                         (om/set-state! owner [:current-save-slot] 5))
                                                                       ["ctrl+6"]     #(do
                                                                                         (restore-save-state! owner 6)
                                                                                         (om/set-state! owner [:current-save-slot] 6))
                                                                       ["ctrl+7"]     #(do
                                                                                         (restore-save-state! owner 7)
                                                                                         (om/set-state! owner [:current-save-slot] 7))
                                                                       ["ctrl+8"]     #(do
                                                                                         (restore-save-state! owner 8)
                                                                                         (om/set-state! owner [:current-save-slot] 8))
                                                                       ["ctrl+9"]     #(do
                                                                                         (restore-save-state! owner 9)
                                                                                         (om/set-state! owner [:current-save-slot] 9))})}})
         (dom/div
          {:className     "debugger"
           :on-mouse-move #(.stopPropagation %)}
          (when (om/get-state owner [:open?])
            (bs-p/panel
             {:header (dom/div "["
                               (dom/button {:style    {:cursor "pointer"}
                                            :on-click #(om/set-state! owner [:tool] :timeline)} "Timeline")
                               "]  ["
                               (dom/button {:style    {:cursor "pointer"}
                                            :on-click #(om/set-state! owner [:tool] :components)} "Components")
                               "]  ["
                               (dom/button {:style    {:cursor "pointer"}
                                            :on-click #(om/set-state! owner [:tool] :states)} "States")
                               "]  ["
                               (dom/button {:style    {:cursor "pointer"}
                                            :on-click #(om/set-state! owner [:tool] :query)} "Expression Watch")
                               "] - Dato Debugger " (dom/small "(" log-count " states)"))}
             (case (om/get-state owner [:tool])
               :states     (dom/div
                            (dom/h3 "Click a slot to restore a state")
                            (dom/ul
                             (for [slot-idx (range 0 10)]
                               (let [local-storage-name (str "dato_save_state_" slot-idx)
                                     existing-db        (js/localStorage.getItem local-storage-name)]
                                 (dom/li {:style    {:cursor "pointer"}
                                          :on-click #(restore-save-state! owner slot-idx)} (if existing-db
                                          (dom/strong "Slot " slot-idx)
                                          "No state saved"))))))
               :query      (dom/div
                            (g/grid
                             {}
                             (g/row
                              {}
                              (g/col
                               {:xs 0 :sm 2 :md 2 :lg 2}
                               (g/row
                                {}
                                (g/col
                                 {:xs 12 :sm 12 :md 12 :lg 12}
                                 (dom/input {:placeholder "Search Expressions"})))
                               (g/row
                                {}
                                (g/col
                                 {:xs 12 :sm 12 :md 12 :lg 12}
                                 (dom/ul
                                  (for [[idx query] (map-indexed vector (om/get-state owner [:query]))
                                        :let [current-query? (= (:title query) (om/get-state owner [:current-query]))
                                              title (str idx ". " (:title query))]]
                                    (dom/li {:style    {:cursor "pointer"}
                                             :on-click #(om/set-state! owner [:current-query] idx)}
                                            (if current-query?
                                              (dom/strong title)
                                              title)))))))
                              (g/col {:xs 3 :sm 3 :md 3 :lg 3}
                                     (let [current-query (om/get-state owner [:query (om/get-state owner [:current-query])])]
                                       (dom/div
                                        (when (false? (:valid? current-query))
                                          (dom/pre "Invalid code"))
                                        (dom/input {:placeholder "Query title"
                                                    :value "Current Session"})
                                        (dom/textarea {:placeholder "Expression (cljs code)"
                                                       :value       (:str current-query)
                                                       :on-change   (fn [event]
                                                                      (let [str-value (.. event -target -value)
                                                                            valid? (try (compile-expr str-value identity)
                                                                                        true
                                                                                        (catch js/Error e
                                                                                          false))]
                                                                        (om/update-state! owner [:queries (om/get-state owner [:current-query])] (fn [s]
                                                                                                                                                   (merge s
                                                                                                                                                          {:str    str-value
                                                                                                                                                           :valid? valid?})))))
                                                       :style       {:width  "100%"
                                                                     :height 50}}))))
                              (g/col {:xs 12 :sm 7 :md 7 :lg 7}
                                     (when-let [current-query (om/get-state owner [:query (om/get-state owner [:current-query])])]
                                       (dom/pre
                                        (binding [*print-length* 10]
                                          (let [query-results ((:fn current-query) db)]
                                            (with-out-str (pp/pprint query-results))))))))))
               :editor     (dom/div
                            "Code editor"
                            (dom/link {:rel      "stylesheet"
                                       :property "stylesheet"
                                       :href     "/css/vendor/codemirror/codemirror.css"})
                            (dom/div {:className "editor"}))
               :components (dom/div
                            "Component States"
                            (dom/link {:rel      "stylesheet"
                                       :property "stylesheet"
                                       :href     "/css/vendor/codemirror/codemirror.css"})
                            (dom/div {:className "state"}
                                     (let [components        (state-watcher-coms @state-watchers)
                                           inspected-com-key (om/get-state owner [:inspected-com-key])]
                                       (dom/div
                                        (dom/input {:on-change   #(om/set-state! owner [:com-name-filter] (.. % -target -value))
                                                    :value        (om/get-state owner [:com-name-filter])
                                                    :placeholder "Component Display Name"})
                                        (dom/div
                                         {:style {:float "right"
                                                  :width "50%"}}
                                         (if inspected-com-key
                                           (dom/h4 (com-name @state-watchers inspected-com-key) "(" inspected-com-key ")")
                                           (dom/h4 "No component"))
                                         (when-let [inspected-com-key (om/get-state owner [:inspected-com-key])]
                                           (let [com-states  (com-state @state-watchers inspected-com-key)
                                                 dato-state* (:dato com-states)
                                                 dato-state  (when dato-state*
                                                               (dsu/touch+ dato-state*))
                                                 om-state    (:om com-states)]
                                             (dom/div
                                              "Dato/Recorded"
                                              (dom/pre (with-out-str (pp/pprint dato-state)))
                                              "Om/Transient"
                                              (dom/pre (with-out-str (pp/pprint (->
                                                                                 om-state
                                                                                 (dissoc :dato.lib.db/listener-key)
                                                                                 (dissoc :dato.lib.db/listener-keys)))))))))
                                        (dom/ul
                                         (for [com-key components
                                               :let [com-name (com-name @state-watchers com-key)]
                                               :when (and com-name
                                                          (let [filter (om/get-state owner [:com-name-filter])]
                                                            (if (string/blank? filter)
                                                              true
                                                              (.match (string/lower-case com-name) filter))))]
                                           (let [com-owner  (com-owner @state-watchers com-key)
                                                 owner-node (when com-owner (om/get-node com-owner))]
                                             (dom/li {:style    {:cursor "pointer"}
                                                      ;; Someday we'll figure this out...
                                                      :on-mouse-enter #(when owner-node (highlight-node! owner-node))
                                                      :on-mouse-leave #(when owner-node (unhighlight-node! owner-node))
                                                      :on-click #(om/set-state! owner [:inspected-com-key] com-key)} com-name "(" com-key ")"))))))))
               :timeline   (dom/div
                            (dom/h3 (pr-str (get-in log [selected-state-idx :tx/intent])))
                            (dom/div
                             (dom/input {:type      "range"
                                         :min       0
                                         :max       (max 0 (dec (count (:log data))))
                                         :value     (om/get-state owner [:states :selected])
                                         :on-change #(let [idx (reader/read-string (.. % -target -value))]
                                                       (om/set-state! owner [:states :selected] idx)
                                                       (set-app-state! idx))})
                             (dom/button {:on-click #(play-all-states!)
                                          :disabled (not (pos? log-count))} "> Play All")
                             (dom/div {:style {:float "right"
                                               :width "50%"}}
                                      (dom/pre (pr-str (get-in selected-state [:tx :tx-data]))))
                             (dom/ul
                              {:className "event-history"}
                              (let [int (->> (:log data)
                                             (map-indexed vector)
                                             (reduce (fn [run [idx next]]
                                                       (let [cnt        (count run)
                                                             last-event (:tx/intent (nth run (dec cnt)))
                                                             selected?  (= idx selected-state-idx)]
                                                         (if (= last-event (:tx/intent next))
                                                           (-> run
                                                               (update-in [(dec cnt) :count] inc)
                                                               (update-in [(dec cnt) :selected?] #(or selected? %)))
                                                           (conj run (-> next
                                                                         (assoc :count 1)
                                                                         (assoc :selected? selected?)))))) [{}]))]
                                (for [tx int]
                                  (dom/li (if (:selected? tx)
                                            (dom/strong (pr-str (:tx/intent tx)))
                                            (pr-str (:tx/intent tx)))
                                          (when (pos? (dec (:count tx))) (str " x " (:count tx))))))))))))))))))
