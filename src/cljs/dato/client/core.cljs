(ns ^:figwheel-always dato.client.core
    (:require [cljs.core.async :as async :refer [put! chan <!]]
              [datascript :as d]
              [devtools.core :as devtools]
              ;;[figwheel.client :as figwheel :include-macros true]
              [dato.client.components.root :as com-root]
              [dato.client.utils :as utils :refer [sel1]]
              [dato.lib.core :as dato]
              [dato.lib.db :as db]
              [om.core :as om :include-macros true]
              [om-i.core :as om-i]
              [om-i.hacks :as om-i-hacks]
              ;;[weasel.repl :as weasel]
              )
    (:require-macros [cljs.core.async.macros :as async :refer [go]]))

;; (enable-console-print!)

;; (defonce setup-misc
;;   (do
;;     (om-i-hacks/insert-styles)
;;     (om-i/setup-component-stats!)
;;     ;; Enable https://github.com/binaryage/cljs-devtools
;;     (devtools/install!)))

;; ;; Put this here so it's easy to query in the repl
;; (defonce db
;;   (d/create-conn))

;; (defn -main [root-node state]
;;   (let [container (sel1 root-node "div.app-instance")
;;         dato      (:dato @state)
;;         dato-ch   (get-in dato [:comms :dato])
;;         conn      (get-in dato [:db])]
;;     (om/root com-root/root-com state
;;              {:target     container
;;               :shared     {:dato dato}
;;               :instrument (fn [f cursor m]
;;                             (om/build* f cursor
;;                                        (if (:descriptor m)
;;                                          m
;;                                          (assoc m :descriptor om-i/instrumentation-methods))))})
;;     (when-not (dato/bootstrapped? @conn)
;;       ;; Grab the DataScript Schema (and enums), and the session ID to
;;       ;; start.
;;       (dato/bootstrap! dato)
;;       (go
;;         ;; Wait until we have the schema and session-id
;;         (let [[bootstrap-success? session-id] (<! dato-ch)]
;;           ;; Retrieve all remote entities that have a :task/title
;;           ;; attr. The result of this will be handled in the root
;;           ;; component multimethod with a name of :server/find-tasks-succeeded
;;           (dato/r-qes-by dato {:name :find-tasks
;;                                :a    :task/title})
;;           (dato/start-loop! dato))))))

;; (defonce app-state
;;   (let [ws-host (str js/window.location.hostname ":" "8080")
;;         ws-path "/ws/index.html"
;;         app-dato (dato/new-dato ws-host ws-path db db/cs-schema)]
;;     (atom {:dato app-dato})))

;; (-main (js/document.getElementById "dato-app") app-state)

(defonce connected?
  (do
    (figwheel/watch-and-reload
     :websocket-url "ws://localhost:3449/figwheel-ws"
     :jsload-callback (fn []
                        (js/console.log "Reloading from figwheel..")))
    (weasel/connect "ws://localhost:9001" :verbose true :print #{:repl :console})
    true))

(defn ^:export inspect-state []
  (clj->js @app-state))
