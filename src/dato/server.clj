(ns dato.server
  (:require [compojure.core :refer [defroutes GET]]
            [compojure.route :as route]
            [dato.datomic :as datod]
            [dato.dev :as dev]
            [dato.lib.server :as dato]
            [environ.core :as config]
            [hiccup.core :as h]
            [immutant.web :as iw]
            [immutant.web.middleware :as imw]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.defaults :as ring-defaults]
            [ring.middleware.multipart-params :as multipart]
            [ring.middleware.reload :as reload]
            [ring.middleware.resource :as ring-resource]
            [ring.util.response :as resp]))

(def is-dev?
  (config/env :is-dev))

(def bootstrap-html
  (let [header [:head
                [:link {:href "/css/style.css" :rel "stylesheet" :type "text/css"}]
                [:link {:href "/css/base.css" :rel "stylesheet" :type "text/css"}]
                [:link {:href "/css/index.css" :rel "stylesheet" :type "text/css"}]
                [:title "Dato • TodoMVC"] ]]
    (h/html [:html
             header
             [:body
              [:div#dato-app
               [:input.history {:style "display:none;"}]
               [:div.app-instance "Please wait while the app loads..."]]
              (if is-dev?
                [:script {:src "/js/bin-debug/main.js"}]
                [:script {:src "/js/bin/main.js"}])]])))

(def dato-server
  (dato/map->DatoServer {:routing-table dato/default-routing-table}))

(defroutes routes
  (route/resources "/")
  (GET "/*" req bootstrap-html))

(def http-handler
  (as-> (var routes) routes
    (ring-defaults/wrap-defaults routes ring-defaults/api-defaults)
    (multipart/wrap-multipart-params routes)
    (if is-dev?
      (reload/wrap-reload routes)
      routes)))

(defn run-web-server [& [port]]
  (let [port (Integer. (or port (config/env :port) 10555))]
    (print "Starting web server on port" port ".\n")
    (jetty/run-jetty http-handler {:port port :join? false})))

(defn handler [{c :context}]
  (resp/redirect (str c "/index.html")))

(defn ?assign-id [handler]
  (fn [request]
    (let [id        (get-in request [:session :id] (dato/new-session-id))
          mouse-pos [0 0]
          response  (handler (-> request
                                 (assoc-in [:session :id] id)
                                 (assoc-in [:session :mouse :pos] mouse-pos)
                                 (assoc-in [:session :live :dato] dato-server)))]
      response)))

(defn create-websocket []
  (iw/run
   (-> (var handler)
       (ring-resource/wrap-resource "public")
       (imw/wrap-websocket {:on-message (var dato/on-message)
                            :on-open    (var dato/on-open)
                            :on-close   (var dato/on-close)})
       ?assign-id
       (imw/wrap-session))
   {:path "/ws"
    :host "0.0.0.0"}))

(defn run [& [port]]
  (when is-dev?
    (dev/start-figwheel!))
  (create-websocket)
  (dato/setup-tx-report-ch (datod/conn))
  (def stop-tx-broadcast-ch (dato/start-tx-broadcast! dato/tx-report-mult))
  (run-web-server port))

(defn -main [& [port]]
  (run port))
