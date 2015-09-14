(ns dato.lib.debug
  (:require [clojure.java.io :as io]
            [cljs.js-deps :as js-deps]
            ))

(defn closure-index []
  (let [paths-to-provides
        (map (fn [[_ path provides]]
               [path (map second
                          (re-seq #"'(.*?)'" provides))])
             (re-seq  #"\ngoog\.addDependency\('(.*)', \[(.*?)\].*" (slurp (io/resource "goog/deps.js"))))]
    (into {}
          (for [[path provides] paths-to-provides
                provide provides]
            [(symbol provide) (str "goog/" (second (re-find #"(.*)\.js$" path)))]))))

(defn source-path->source [macros? path]
  (let [file-info (some (fn [ext] (when-let [file (io/resource (str path "." ext))]
                                   {:file file
                                    :lang (if (= ext "js")
                                            "js"
                                            "clj")})) (if macros?
                                                        ["cljc" "clj"]
                                                        ["cljc" "cljs" "js"]))]
    (or (try {:src (slurp (:file file-info))
              :lang (:lang file-info)} (catch Exception e nil))
        (try {:src (slurp (first (js-deps/find-js-resources (str path ".js"))))
              :lang "js"} (catch Exception e nil))
        (try {:src (slurp (io/file (str "yaks/dato/src/cljs/" path ".cljs")))
              :lang "clj"} (catch Exception e nil))
        (try {:src (slurp (io/file (str "yaks/datascript/src/" path ".cljs")))
              :lang "clj"} (catch Exception e nil))
        (source-path->source macros? (get (closure-index) 'goog.Uri)))))

(comment
  ;; To server clj/c/s files for client-side compilation, need a route like
  (GET "/_source" request
    (let [path    (get-in request [:params :path])
          macros? (get-in request [:params :macros])]
      (println "Macros?/PATH: " macros? path)
      {:body (json/generate-string (source-path->source macros? path))})))
