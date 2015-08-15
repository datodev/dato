(defproject dato "0.1.0-SNAPSHOT"
  ;; See profiles.clj for profiles
  :description "FIXME: write description"
  :url ""

  :license {:name "Eclipse Public License"
            :url  "http://www.eclipse.org/legal/epl-v10.html"}

  :repositories {"my.datomic.com" {:url   "https://my.datomic.com/repo"
                                   :creds :gpg}}
  :plugins [[lein-cljsbuild "1.0.6" :exclusions [org.clojure/clojurescript]]
            [lein-environ "1.0.0"]]

  :pedantic? :abort

  :exclusions [[org.clojure/clojure]
               [org.clojure/clojurescript]
               [org.slf4j/log4j-over-slf4j]]

  :dependencies [ ;; Chrome extension for cljs dev
                 [binaryage/devtools "0.3.0"]
                 [cheshire "5.5.0" :exclusions [com.fasterxml.jackson.core/jackson-core]]
                 [clj-http "1.1.2" :exclusions [com.fasterxml.jackson.dataformat/jackson-dataformat-smile com.fasterxml.jackson.core/jackson-core]]

                 ;; Internal conflict with xml-apis versions, be sure to update both when you
                 ;; update clj-pdf
                 [clj-pdf "2.1.0" :exclusions [xml-apis]]
                 [xml-apis "1.3.04"]

                 [cljsjs/react "0.12.2-8"]
                 [com.cognitect/transit-clj "0.8.275" :exclusions [com.fasterxml.jackson.core/jackson-core]]
                 [com.cognitect/transit-cljs "0.8.220" :exclusions [org.clojure/clojurescript]]
                 [com.datomic/datomic-pro "0.9.5153" :exclusions [org.slf4j/slf4j-nop joda-time org.slf4j/slf4j-api org.clojure/clojurescript]]
                 [com.fasterxml.jackson.core/jackson-annotations "2.3.1"]
                 [com.fasterxml.jackson.core/jackson-core "2.3.1"]
                 [compojure "1.3.1"]
                 ;;[datascript "0.11.6"]
                 [environ "1.0.0"]
                 [fipp "0.6.2"]
                 [hiccup "1.0.5"]
                 [instaparse "1.4.0"]
                 [markdown-clj "0.9.66"]
                 [org.omcljs/om "0.8.8"]
                 [org.clojure/clojure "1.7.0-RC2"]
                 [org.clojure/clojurescript "0.0-3308" :exclusions [org.clojure/tools.reader org.clojure/data.json]]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [org.clojure/data.json "0.2.6" :classifier "aot"]
                 [org.clojure/google-closure-library "0.0-20150505-021ed5b3"]
                 [org.clojure/google-closure-library-third-party "0.0-20150505-021ed5b3"]
                 [org.clojure/test.check "0.7.0"]
                 [org.clojure/tools.logging "0.2.6"]
                 [log4j "1.2.17"]
                 [log4j/apache-log4j-extras "1.1"]
                 [org.slf4j/slf4j-api "1.7.10"]
                 [org.slf4j/slf4j-log4j12 "1.7.10" :exclusions [log4j]]
                 [org.clojure/tools.reader "0.10.0-alpha1"]
                 [org.immutant/web "2.0.2" :exclusions [org.clojure/clojure
                                                        org.clojure/tools.reader
                                                        org.clojure/clojurescript
                                                        org.jboss.logging/jboss-logging
                                                        org.slf4j/slf4j-nop
                                                        org.slf4j/slf4j-api
                                                        org.slf4j/slf4j-simple
                                                        org.slf4j/slf4j-log4j12
                                                        ch.qos.logback/logback-classic]]
                 [org.immutant/immutant-transit "0.2.3" :exclusions [com.fasterxml.jackson.core/jackson-core]]
                 [org.postgresql/postgresql "9.3-1102-jdbc41"]
                 [org.postgresql/postgresql "9.3-1102-jdbc41"]
                 [pdfboxing "0.1.5" :exclusions [log4j/log4j]]
                 [precursor/om-i "0.1.7"]
                 [prismatic/om-tools "0.3.11" :exclusions [om]]
                 [prismatic/schema "0.4.3"]
                 [racehub/om-bootstrap "0.5.1"]
                 [ring "1.3.2"]
                 [ring/ring-defaults "0.1.3"]
                 [sablono "0.3.4"]
                 [org.clojure/tools.nrepl "0.2.10"]]

  :cljsbuild {:test-commands {"test" ["phantomjs" "env/test/js/unit-test.js" "env/test/unit-test.html"]}
              :builds        [{:id           "dev"
                               :source-paths ["src/cljs" "src/shared/" "yaks/datascript/src" "yaks/datascript/bench/src"]
                               :figwheel     true
                               :compiler     {:asset-path    "/js/bin-debug"
                                              :main          dato.client.core
                                              :output-to     "resources/public/js/bin-debug/main.js"
                                              :output-dir    "resources/public/js/bin-debug/"
                                              :optimizations :none
                                              :pretty-print  true
                                              :preamble      ["react/react.js"]
                                              :externs       ["react/externs/react.js"]
                                              :source-map    true
                                              :warnings      {:single-segment-namespace false}}}
                              {:id "test"
                               :source-paths ["src/cljs" "test/cljs" "yaks/datascript/src" "yaks/datascript/bench/src"]
                               :compiler {:pretty-print  true
                                          :output-to     "resources/public/cljs/test/frontend-dev.js"
                                          :output-dir    "resources/public/cljs/test"
                                          :optimizations :advanced
                                          :externs       ["datascript/externs.js"]
                                          :source-map    "resources/public/cljs/test/sourcemap-dev.js"
                                          :warnings      {:single-segment-namespace false}}}
                              {:id "production"
                               :source-paths ["src/cljs" "src/shared/" "yaks/datascript/src" "yaks/datascript/bench/src"]
                               :compiler     {:asset-path    "/js/bin"
                                              :main          dato.client.core
                                              :output-to     "resources/public/js/bin/main.js"
                                              :output-dir    "resources/public/js/bin/"
                                              :optimizations :advanced
                                              :pretty-print  false
                                              :preamble      ["react/react.js"]
                                              :externs       ["react/externs/react.js"]
                                              :warnings      {:single-segment-namespace false}}
                               :jar true}]}

  :main ^:skip-aot dato.init

  :source-paths ["src/" "src/shared/" "yaks/datascript/src" "yaks/datascript/bench/src"]

  :target-path "target/%s")
