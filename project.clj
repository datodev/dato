(defproject dato "0.1.0-SNAPSHOT"
  ;; See profiles.clj for profiles
  :description "FIXME: write description"
  :url ""

  :license {:name "Eclipse Public License"
            :url  "http://www.eclipse.org/legal/epl-v10.html"}

  :repositories {"my.datomic.com" {:url   "https://my.datomic.com/repo"
                                   :creds :gpg}}

  :pedantic? :abort

  :exclusions [[org.clojure/clojure]
               [org.clojure/clojurescript]
               [org.slf4j/log4j-over-slf4j]]

  :dependencies [ ;; Chrome extension for cljs dev
                 [cheshire "5.5.0" :exclusions [com.fasterxml.jackson.core/jackson-core]]
                 [clj-http "1.1.2" :exclusions [com.fasterxml.jackson.dataformat/jackson-dataformat-smile com.fasterxml.jackson.core/jackson-core]]

                 [cljsjs/react "0.12.2-8"]
                 [com.cognitect/transit-clj "0.8.275" :exclusions [com.fasterxml.jackson.core/jackson-core]]
                 [com.cognitect/transit-cljs "0.8.220" :exclusions [org.clojure/clojurescript]]
                 [com.datomic/datomic-pro "0.9.5206" :exclusions [org.slf4j/slf4j-nop org.clojure/clojure org.slf4j/log4j-over-slf4j joda-time org.slf4j/slf4j-api org.clojure/clojurescript com.fasterxml.jackson.core/jackson-core]]
                 [com.fasterxml.jackson.core/jackson-annotations "2.3.1"]
                 [com.fasterxml.jackson.core/jackson-core "2.3.1"]

                 [garden "1.3.0-SNAPSHOT"]

                 [org.omcljs/om "0.8.8"]
                 [org.clojure/clojure "1.7.0-RC2"]
                 [org.clojure/clojurescript "1.7.122" :exclusions [org.clojure/clojure org.clojure/tools.reader org.clojure/clojurescript
                                                                   org.clojure/google-closure-library-third-party]]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [org.clojure/data.json "0.2.6" :classifier "aot"]
                 [org.clojure/google-closure-library "0.0-20150805-acd8b553" :exclusions [org.clojure/google-closure-library-third-party org.clojure/clojure org.clojure/clojurescript]]
                 [org.clojure/google-closure-library-third-party "0.0-20150505-021ed5b3"]
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
                 [org.clojure/tools.nrepl "0.2.10"]
                 [talaria "0.1.3"]]

  :source-paths ["src/" "src/shared/" "yaks/datascript/src" "yaks/datascript/bench/src"]

  :target-path "target/%s")
