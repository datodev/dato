(ns dato.server
  (:require [compojure.core :refer [defroutes GET]]
            [compojure.route :as route]
            [dato.datomic :as datod]
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

;; [session all-sessions & args]
(defn add [_ _ x y]
  (+ x y))

(defn sum [_ _ & numbers]
  (reduce + 0 numbers))

(defn my-reverse [_ _ list]
  (vec (reverse list)))

(def dato-routes
  (dato/new-routing-table {[:ss/add]     {:handler add}
                           [:ss/sum]     {:handler sum}
                           [:ss/reverse] {:handler my-reverse}}))

(def dato-server
  (dato/map->DatoServer {:routing-table #'dato-routes
                         :conn-fn       datod/conn}))

(defn run [& [port]]
  (dato/start! handler {:server (var dato-server)
                        :port   8080})
  (run-web-server port))

(defn -main [& [port]]
  (run port))
