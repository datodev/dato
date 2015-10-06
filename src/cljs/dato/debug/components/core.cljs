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
            [garden.core :as gc :refer [css]]
            [garden.selectors :as gs]
            [garden.units :as gu :refer [px pt]]
            [goog.dom.classes :as gclass]
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

(def debugger-styles
  [[:.debugger-container {:position "fixed"
                          :left     (px 0)
                          :bottom   (px 0)
                          :width    "100%"
                          :height   "50%"
                          :z-index  1501}
    [:.dato-debugger {:background-color "#eee"
                      :height "100%"}
     [:.selected-item {:font-weight "bolder"}]
     [:.dato-selector {:cursor "pointer"}]
     [:.debugger-head
      [:button {:border-width  (px 1)
                :border-color  "#999"
                :border-style  "solid"
                :border-radius (px 2)
                :padding       (px 8)
                :margin-left   (px 4)
                :cursor        "pointer"}
       [:&.selected {:border-width  (px 2)
                     :border-radius (px 4)
                     :font-weight   "bolder"}]]]
     [:.dato-debugger-query
      [:.query-inspector {:width "80%"
                         :float "right"}]
      [:.query-listing {:width "20%"}]
      [:.code-editor {:width  "20%"
                      :height 50}]]
     [:.dato-debugger-components
      [:.component-listing {:float "right"
                            :width "50%"}]]
     [:.dato-debugger-timeline
      [:.selected-event {:font-weight "bolder"}]
      [:.event-inspector {:float "right"
                          :width "85%"}]
      [:.event-listing {:width "15%"}]]]]])

(def debugger-styles-str
  (apply css [{:vendors ["webkit ie o moz"]}] debugger-styles))

(defn save-to-local-state! [owner]
  (let [state (-> (om/get-state owner)
                  (select-keys [:open?
                                :current-save-slot :current-query
                                :tool
                                [:states :selected]])
                  (pr-str))]
    (js/localStorage.setItem "dato-debugger-settings" state)))

(defn devtools* [data owner opts]
  (reify
    om/IDisplayName
    (display-name [_]
      "DatoDevtools")
    om/IWillMount
    (will-mount [_]
      (let [local-storage-state (reader/read-string (or (js/localStorage.getItem "dato-debugger-settings") "{}"))]
        (om/set-state! owner [:current-save-slot] (:current-save-slot local-storage-state 1))
        (om/set-state! owner [:tool] (:tool local-storage-state :timeline))
        (om/set-state! owner [:current-query] (:current-query local-storage-state 0))
        (om/set-state! owner [:open?] (or (:open? local-storage-state false)))
        (om/set-state! owner [:query] (vec (concat (or (:expressions opts) [])
                                                   (:expressions local-storage-state))))))
    om/IDidUpdate
    (did-update [_ _ prev]
      (let [node                (om/get-node owner)
            editor-node         (.querySelector node ".editor")
            switched-to-editor? (and (not= :debugger (:tool prev))
                                     (= :debugger (om/get-state owner [:tool])))]
        (save-to-local-state! owner)
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
            restore-state!      (fn [new-db]
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
                                     :else                            (js/setTimeout #(play-all-states! (inc idx)) 1))))
            l-state             (om/get-state owner)]
        (dom/div
         (dom/div
          {:className "dato-debugger"}
          (om/build keyboard/keyboard-handler {} {:opts {:keymap (atom {["ctrl+s"]       #(save-state! owner (om/get-state owner [:current-save-slot]))
                                                                        ["ctrl+r"]       #(restore-save-state! owner (om/get-state owner [:current-save-slot]))
                                                                        ["ctrl+0"]       #(om/set-state! owner [:current-save-slot] 0)
                                                                        ["ctrl+1"]       #(om/set-state! owner [:current-save-slot] 1)
                                                                        ["ctrl+2"]       #(om/set-state! owner [:current-save-slot] 2)
                                                                        ["ctrl+3"]       #(om/set-state! owner [:current-save-slot] 3)
                                                                        ["ctrl+4"]       #(om/set-state! owner [:current-save-slot] 4)
                                                                        ["ctrl+5"]       #(om/set-state! owner [:current-save-slot] 5)
                                                                        ["ctrl+6"]       #(om/set-state! owner [:current-save-slot] 6)
                                                                        ["ctrl+7"]       #(om/set-state! owner [:current-save-slot] 7)
                                                                        ["ctrl+8"]       #(om/set-state! owner [:current-save-slot] 8)
                                                                        ["ctrl+9"]       #(om/set-state! owner [:current-save-slot] 9)
                                                                        ["shift+ctrl+0"] #(do
                                                                                            (restore-save-state! owner 0)
                                                                                            (om/set-state! owner [:current-save-slot] 0))
                                                                        ["shift+ctrl+1"] #(do
                                                                                            (restore-save-state! owner 1)
                                                                                            (om/set-state! owner [:current-save-slot] 1))
                                                                        ["shift+ctrl+2"] #(do
                                                                                            (restore-save-state! owner 2)
                                                                                            (om/set-state! owner [:current-save-slot] 2))
                                                                        ["shift+ctrl+3"] #(do
                                                                                            (restore-save-state! owner 3)
                                                                                            (om/set-state! owner [:current-save-slot] 3))
                                                                        ["shift+ctrl+4"] #(do
                                                                                            (restore-save-state! owner 4)
                                                                                            (om/set-state! owner [:current-save-slot] 4))
                                                                        ["shift+ctrl+5"] #(do
                                                                                            (restore-save-state! owner 5)
                                                                                            (om/set-state! owner [:current-save-slot] 5))
                                                                        ["shift+ctrl+6"] #(do
                                                                                            (restore-save-state! owner 6)
                                                                                            (om/set-state! owner [:current-save-slot] 6))
                                                                        ["shift+ctrl+7"] #(do
                                                                                            (restore-save-state! owner 7)
                                                                                            (om/set-state! owner [:current-save-slot] 7))
                                                                        ["shift+ctrl+8"] #(do
                                                                                            (restore-save-state! owner 8)
                                                                                            (om/set-state! owner [:current-save-slot] 8))
                                                                        ["shift+ctrl+9"] #(do
                                                                                            (restore-save-state! owner 9)
                                                                                            (om/set-state! owner [:current-save-slot] 9))})}})
          (dom/div
           {:className     "debugger"
            :on-mouse-move #(.stopPropagation %)}
           (when (:open? l-state)
             (dom/div
              (dom/style {:scoped true} debugger-styles-str)
              (dom/div
               {:className "debugger-head"}
               (let [coms [{:name  :timeline
                            :title "History"}
                           {:name  :components
                            :title "Components"}
                           {:name  :states
                            :title "Saved States"}
                           {:name  :query
                            :title "Expression Watch"}]]
                 (for [com-desc coms]
                   (dom/button {:className (when (= (:tool l-state) (:name com-desc))
                                             "selected")
                                :on-click #(om/set-state! owner [:tool] (:name com-desc))}
                               (:title com-desc))))
               " - Dato Debugger")
              (dom/div
               (case (:tool l-state)
                 :states     (dom/div
                              (dom/h3 "Current slot: " (:current-save-slot l-state))
                              (dom/ul
                               (for [slot-idx (range 1 10)]
                                 (let [local-storage-name (str "dato_save_state_" slot-idx)
                                       existing-db        (js/localStorage.getItem local-storage-name)]
                                   (dom/li {:className (str "dato-selector " (when (= slot-idx (:current-save-slot l-state))))
                                            :on-click  #(do
                                                          (om/set-state! owner [:current-save-slot] slot-idx)
                                                          (restore-save-state! owner slot-idx))}
                                           (if existing-db
                                             (str "Slot " slot-idx)
                                             "No state saved"))))))
                 :query      (dom/div
                              {:className "dato-debugger-query"}
                              (when-let [current-query (get-in l-state [:query (:current-query l-state)])]
                                (dom/div
                                 {:className "query-inspector"}
                                 (dom/pre
                                  (binding [*print-length* 10]
                                    (let [query-results ((:fn current-query) db)]
                                      (with-out-str (pp/pprint query-results)))))))
                              (let [current-query (get-in l-state [:query (:current-query l-state)])]
                                (dom/div
                                 {:className "query-editor"}
                                 (when (false? (:valid? current-query))
                                   (dom/pre "Invalid code"))))
                              (dom/input {:placeholder "Search Expressions"})
                              (dom/div
                               (dom/ul
                                {:className "query-listing"}
                                (for [[idx query] (map-indexed vector (:query l-state))
                                      :let [current-query? (= idx (:current-query l-state))
                                            title (str idx ". " (:title query))]]
                                  (dom/li {:className (str "dato-selector " (when current-query? "selected-item"))
                                           :on-click #(om/set-state! owner [:current-query] idx)}
                                          title)))))
                 :editor     (dom/div
                              "Code editor"
                              (dom/link {:rel      "stylesheet"
                                         :property "stylesheet"
                                         :href     "/css/vendor/codemirror/codemirror.css"})
                              (dom/div {:className "editor"}))
                 :components (dom/div
                              "Component States"
                              (dom/div {:className "dato-debugger-components"}
                                       (let [components        (state-watcher-coms @state-watchers)
                                             inspected-com-key (:inspected-com-key l-state)]
                                         (dom/div
                                          (dom/input {:on-change   #(om/set-state! owner [:com-name-filter] (.. % -target -value))
                                                      :value        (:com-name-filter l-state)
                                                      :placeholder "Component Display Name"})
                                          (dom/div
                                           {:className "component-listing"}
                                           (if inspected-com-key
                                             (dom/h4 (com-name @state-watchers inspected-com-key) "(" inspected-com-key ")")
                                             (dom/h4 "No component"))
                                           (when-let [inspected-com-key (:inspected-com-key l-state)]
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
                                                            (let [filter (:com-name-filter l-state)]
                                                              (if (string/blank? filter)
                                                                true
                                                                (.match (string/lower-case com-name) filter))))]
                                             (let [com-owner  (com-owner @state-watchers com-key)
                                                   owner-node (when com-owner (om/get-node com-owner))]
                                               (dom/li {:className "dato-selector"
                                                        ;; Someday we'll figure this out...
                                                        :on-mouse-enter #(when owner-node (highlight-node! owner-node))
                                                        :on-mouse-leave #(when owner-node (unhighlight-node! owner-node))
                                                        :on-click #(om/set-state! owner [:inspected-com-key] com-key)} com-name "(" com-key ")"))))))))
                 :timeline   (dom/div
                              {:className "dato-debugger-timeline"}
                              (dom/h3 (pr-str (get-in log [selected-state-idx :tx/intent])))
                              (dom/small "(" log-count " states)")
                              (dom/div
                               (dom/input {:type      "range"
                                           :min       0
                                           :max       (max 0 (dec (count (:log data))))
                                           :value     (or (get-in l-state [:states :selected]) 0)
                                           :on-change #(let [idx (reader/read-string (.. % -target -value))]
                                                         (om/set-state! owner [:states :selected] idx)
                                                         (set-app-state! idx))})
                               (dom/button {:on-click #(play-all-states!)
                                            :disabled (not (pos? log-count))} "> Play All")
                               (dom/div {:className "event-inspector"}
                                        (dom/pre (pr-str (get-in selected-state [:tx :tx-data]))))
                               (dom/ul
                                {:className "event-listing"}
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
                                    (dom/li
                                     {:className (when (:selected? tx) "selected-event")}
                                     (pr-str (:tx/intent tx))
                                     (when (pos? (dec (:count tx))) (str " x " (:count tx))))))))))))))))))))

(defn devtools [data owner opts]
  (reify
    om/IDisplayName
    (display-name [_]
      "DatoDevtoolsContainer")
    om/IWillMount
    (will-mount [_]
      (let [local-storage-state (reader/read-string (or (js/localStorage.getItem "dato-devtools") "{}"))
            saved-keys          (set (keys local-storage-state))
            watch-key           (gensym)]
        (add-watch (get-in data [:dato :history]) watch-key (fn [_]
                                                              (if (and owner (om/mounted? owner))
                                                                (om/refresh! owner)
                                                                (remove-watch (get-in data [:dato :history]) watch-key))))
        (om/set-state! owner {:watch-key     watch-key
                              :ext-devtools? (if (get saved-keys :ext-devtools?)
                                               (:ext-devtools? local-storage-state)
                                               (:ext-devtools? opts))
                              :open?         (if (get saved-keys :open?)
                                               (:open? local-storage-state)
                                               (:open? opts))})))
    om/IWillUnmount
    (will-unmount [_]
      (let [watch-key (om/get-state owner :watch-key)]
        (remove-watch (get-in data [:dato :history]) watch-key)))
    om/IDidUpdate
    (did-update [_ _ prev]
      (let [l-state      (om/get-state owner)
            popping-out? (and (or (not (:ext-devtools? prev))
                                  (not (:open? prev)))
                              (and (:ext-devtools? l-state)
                                   (:open? l-state)))
            popping-in?  (and (:ext-devtools? prev)
                              (or (not (:ext-devtools? l-state))
                                  (not (:open? l-state))))
            ext-win      (if popping-out?
                           (do
                             (let [win (.open js/window nil, "dato-devtools", "menubar=no,location=no,resizable=yes,scrollbars=no,status=no")]
                               (set! (.-onclose win) (fn [] (om/set-state! owner [:ext-devtools?] false)))
                               (.. win -location reload)
                               ;; None of this seems to work to
                               ;; prevent the devtools from stealing
                               ;; focus.
                               (js/setTimeout #(do (.focus js/window)
                                                   (.blur win)) 2050)
                               (om/set-state-nr! owner :ext-win win)
                               win))
                           (:ext-win l-state))
            ext-child    (:ext-child l-state)
            child        (:child l-state)
            to-save-state {:ext-devtools? (:ext-devtools? l-state)
                           :open?         (:open? l-state)}]
        (js/localStorage.setItem "dato-devtools" (pr-str to-save-state))
        (when (and popping-in? ext-win)
          (om/set-state-nr! owner :ext-win nil)
          (.close ext-win))
        (cond
          (and popping-out? ext-win) (js/setTimeout
                                      #(om/set-state! owner :ext-child (om/root devtools* (get-in data [:dato :history])
                                                                                {:target (.. ext-win -document -body)
                                                                                 :shared (om/get-shared owner)
                                                                                 :opts   opts}))
                                      10)
          (and ext-win ext-child) (om/refresh! ext-child))))
    om/IRender
    (render [_]
      (let [l-state (om/get-state owner)
            open?   (:open? l-state)]
        (dom/div
         {:style {:display (when-not open? "none")}}
         (om/build keyboard/keyboard-handler {} {:opts {:keymap (atom {["ctrl+slash"] #(om/update-state! owner [:open?] not)})}})
         (when open?
           (dom/button
            {:on-click #(om/update-state! owner [:ext-devtools?] not)}
            (if (:ext-devtools? l-state)
              (dom/div
               {:style {:position "fixed"
                        :bottom   0
                        :left     0}}
               "⤶")
              "⤴")))
         (when open?
           (when-not (:ext-devtools? l-state)
             (om/build devtools* @(get-in data [:dato :history]) {:opts opts}))))))))
