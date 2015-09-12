{:dev     {:source-paths ["env/dev/cljs/" "src/cljs" "env/dev/clj"]

           :plugins      [[cider/cider-nrepl "0.9.1" :exclusions [org.clojure/tools.nrepl]]
                          [lein-ancient "0.6.7" :exclusions [org.clojure/clojure]]
                          [lein-figwheel "0.3.9-SNAPSHOT" :exclusions [org.clojure/clojure org.codehaus.plexus/plexus-utils compojure commons-codec org.clojure/tools.reader org.clojure/clojurescript]]
                          [refactor-nrepl "1.0.5" :exclusions [org.clojure/clojure org.clojure/tools.nrepl]]]

           :dependencies [[com.cemerick/piggieback "0.2.1" :exclusions [org.clojure/clojure org.clojure/clojurescript org.clojure/tools.nrepl]]
                          [figwheel-sidecar "0.3.9-SNAPSHOT" :exclusions [ring/ring-core org.clojure/clojure org.slf4j/log4j-over-slf4j org.codehaus.plexus/plexus-utils compojure commons-codec org.clojure/tools.reader org.clojure/clojurescript]]
                          [org.clojure/tools.nrepl "0.2.10"]
                          [weasel "0.7.0" :exclusions [http-kit org.clojure/clojurescript]]]

           :repl-options {:init-ns          dato.lib.server
                          :nrepl-middleware [cemerick.piggieback/wrap-cljs-repl]}
           
           :test-paths   ["test/clj"]
           
           :env          {:is-dev true}}

 :uberjar {:source-paths ["env/production/clj" "env/dev/cljs/"]
           :prep-tasks   ["compile" ["cljsbuild" "once"]]
           :env          {:is-dev     false
                          :production true}
           :omit-source  true
           :aot          :all
           :plugins      [[cider/cider-nrepl "0.9.1" :exclusions [org.clojure/tools.nrepl]]
                          [lein-ancient "0.6.7" :exclusions [org.clojure/clojure]]
                          [refactor-nrepl "1.0.5" :exclusions [org.clojure/clojure org.clojure/tools.nrepl]]]

           :dependencies [[org.clojure/tools.nrepl "0.2.10"]]
           :cljsbuild    {:builds [{:app
                                    {:source-paths ["env/production/cljs"]
                                     :compiler
                                     {:optimizations :advanced
                                      :pretty-print  false}}}]}}}
