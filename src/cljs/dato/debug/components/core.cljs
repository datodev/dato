(ns dato.debug.components.core
  (:require [cljs.js :as cljs]
            [cljsjs.codemirror :as cm]
            [cljsjs.codemirror.mode.clojure :as cm-clj]
            [cljs.pprint :as pp]
            [cljs.reader :as reader]
            [dato.lib.core :as dato]
            [dato.debug.components.keyboard-listener :as keyboard]
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

(defn history-com [data owner opts]
  (reify
    om/IWillMount
    (will-mount [_]
      (om/set-state! owner [:tool] :query)
      (om/set-state! owner [:current-query] "default")
      (om/set-state! owner [:open?] true)
      (om/set-state! owner [:query] (or (:expressions opts) [])))
    om/IDidUpdate
    (did-update [_ _ prev]
      (let [node        (om/get-node owner)
            editor-node (.querySelector node ".editor")
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
      (let [log                (:log data)
            log-count          (count log)
            dato               (om/get-shared owner [:dato])
            db                 (dato/db dato)
            app-root           (om/value (om/get-shared owner [:app-root]))
            selected-state-idx (max 0 (min (or (om/get-state owner [:states :selected]) 0) log-count))
            selected-state     (when (pos? log-count)
                                 (nth log selected-state-idx))
            set-app-state!     (fn [idx]
                                 (when-let [state (and (pos? log-count)
                                                       (get-in log [idx :tx :db-after]))]
                                   (let [new-db (om/value state)
                                         conn   (dato/conn dato)]
                                     (js/console.log "new-db: " new-db)
                                     (js/console.log "conn: " conn)
                                     (js/console.log "\t=>" [(type new-db)
                                                             (pr-str (type new-db))
                                                             (satisfies? datascript.core/ISearch new-db)
                                                             (satisfies? datascript.core/IIndexAccess new-db)
                                                             (satisfies? datascript.core/IDB new-db)])
                                     (reset! conn new-db)
                                     (om/refresh! app-root)
                                     (js/console.log "app-root: " app-root)
                                     ;; (js/console.log "Atom? " conn)
                                     ;; (js/console.log "new-db DB? " (datascript.core/db? new-db))
                                     ;; (js/console.log "DB? " (datascript.core/db? @conn))
                                     ;; (js/console.log "post-reset: " conn)
                                     )))
            play-all-states!   (fn play-all-states!
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
         (om/build keyboard/keyboard-handler {} {:opts {:keymap (atom {["ctrl+slash"] #(om/update-state! owner [:open?] not)})}})
         (dom/div
          {:className     "debugger"
           :on-mouse-move #(.stopPropagation %)}
          (bs-p/panel
           {:header (dom/div (dom/button {:on-click #(om/set-state! owner [:tool] :debugger)} "Debugger")
                             (dom/button {:on-click #(om/set-state! owner [:tool] :editor)} "Editor")
                             (dom/button {:on-click #(om/set-state! owner [:tool] :query)} "Expression Watch")
                             " - Dato Debugger " (dom/small "(" log-count " states)"))}
           (case (om/get-state owner [:tool])
             :query    (dom/div
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
                                                                 :height "100%"}}))))
                          (g/col {:xs 12 :sm 7 :md 7 :lg 7}
                                 (when-let [current-query (om/get-state owner [:query (om/get-state owner [:current-query])])]
                                   (dom/pre
                                    (binding [*print-length* 10]
                                      (let [query-results ((:fn current-query) db)]
                                        (with-out-str (pp/pprint query-results))))))))))
             :editor   (dom/div
                        "Code editor"
                        (dom/link {:rel      "stylesheet"
                                   :property "stylesheet"
                                   :href     "/css/vendor/codemirror/codemirror.css"})
                        (dom/div {:className "editor"}))
             :debugger (dom/div
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
                                      (when (pos? (dec (:count tx))) (str " x " (:count tx)))))))))))))))))
