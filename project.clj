(defproject rigui "0.5.0-SNAPSHOT"
  :description "Timing Wheels"
  :url "https://github.com/sunng87/rigui"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.8.0" :scope "provided"]
                 [org.clojure/clojurescript "1.7.228" :scope "provided"]]
  :plugins [[lein-cljsbuild "1.1.2"]]
  :cljsbuild {:builds [{:source-paths ["src"]
                        :compiler {:optimizations :advanced
                                   :output-to "target/js/rigui.js"
                                   :output-dir "target/js/"
                                   :pretty-print true
                                   :source-map "target/js/rigui.js.map"
                                   :target :nodejs
                                   :language-in :ecmascript5
                                   :language-out :ecmascript5}}]}
  :aliases {"build-cljs-test" ["trampoline" "run" "-m" "clojure.main" "build.clj"]})

;;
