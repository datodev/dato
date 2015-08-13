{:dev     {:source-paths ["src/cljs" "env/dev/clj"]

           :plugins      [[cider/cider-nrepl "0.9.1" :exclusions [org.clojure/tools.nrepl]]
                          [lein-ancient "0.6.7"]
                          [lein-figwheel "0.3.3" :exclusions [org.clojure/clojure org.codehaus.plexus/plexus-utils compojure]]
                          [refactor-nrepl "1.0.5" :exclusions [org.clojure/clojure org.clojure/tools.nrepl]]]

           :dependencies [[com.cemerick/piggieback "0.2.1" :exclusions [org.clojure/clojure org.clojure/clojurescript org.clojure/tools.nrepl]]
                          [figwheel-sidecar "0.3.3" :exclusions [org.clojure/clojure commons-codec org.clojure/clojurescript org.clojure/tools.reader org.codehaus.plexus/plexus-utils org.clojure/tools.namespace medley clout compojure]]
                          [org.clojure/tools.nrepl "0.2.10"]
                          [weasel "0.7.0-SNAPSHOT" :exclusions [http-kit org.clojure/clojurescript]]]

           :repl-options {:init-ns          dato.server
                          :nrepl-middleware [cemerick.piggieback/wrap-cljs-repl]}
           
           :test-paths   ["test/clj"]
           
           :figwheel     {:http-server-root "public"
                          :server-port      3449
                          :nrepl-port       7888
                          :css-dirs         ["resources/public/css"]}

           :env          {:is-dev true}}

 :uberjar {:source-paths ["env/prod/clj"]
           :hooks        [leiningen.cljsbuild]
           :env          {:is-dev false
                          :production true}
           :omit-source  true
           :aot          :all
           :cljsbuild    {:builds [{:app
                                     {:source-paths ["env/prod/cljs"]
                                      :compiler
                                      {:optimizations :advanced
                                       :pretty-print  false}}}]}}}
