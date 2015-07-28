(ns dato.dev
  (:require [environ.core :as config]
            [cemerick.piggieback :as piggieback]
            [weasel.repl.websocket :as weasel]
            [figwheel-sidecar.auto-builder :as fig-auto]
            [figwheel-sidecar.core :as fig]
            [clojurescript-build.auto :as auto]
            [clojure.java.shell :refer [sh]]))

(def is-dev?
  (config/env :is-dev))

(defn browser-repl []
  (let [repl-env (weasel/repl-env :ip "0.0.0.0" :port 9001)]
    (piggieback/cljs-repl repl-env)))

(defn start-figwheel! []
  (let [server (fig/start-server { :css-dirs ["resources/public/css"] })
        config {:builds [{:id           "dev"
                          :source-paths ["src/cljs" "src/shared/" "yaks/datascript/src" "yaks/datascript/bench/src"]
                          :figwheel     true
                          :compiler     {:asset-path    "/js/bin-debug"
                                         :main          'dato.client.core
                                         :output-to     "resources/public/js/bin-debug/main.js"
                                         :output-dir    "resources/public/js/bin-debug/"
                                         :optimizations :none
                                         :pretty-print  true
                                         :preamble      ["react/react.js"]
                                         :externs       ["react/externs/react.js"]
                                         :source-map    true
                                         :warnings      {:single-segment-namespace false}}}]
                :figwheel-server server}]
    (fig-auto/autobuild* config)))
