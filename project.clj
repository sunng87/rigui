(defproject rigui "0.5.3-SNAPSHOT"
  :description "Timing Wheels"
  :url "https://github.com/sunng87/rigui"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.8.0" :scope "provided"]
                 [org.clojure/clojurescript "1.9.562" :scope "provided"]
                 [org.clojure/core.async "0.3.443" :scope "provided"]]
  :plugins [[lein-cljsbuild "1.1.6"]
            [lein-doo "0.1.7"]]
  :cljsbuild {:builds [{:id "core"
                        :source-paths ["src"]
                        :compiler {:optimizations :advanced
                                   :output-to "target/js/rigui.js"
                                   :output-dir "target/js/"
                                   :pretty-print true
                                   :source-map "target/js/rigui.js.map"
                                   :target :nodejs
                                   :language-in :ecmascript5
                                   :language-out :ecmascript5}}
                       {:id "test-node"
                        :source-paths ["src" "test" "doo-test"]
                        :compiler {:output-to "target/js/testable.js"
                                   :output-dir "target/js/"
                                   :main rigui.runner
                                   :target :nodejs
                                   :optimizations :none}}]}
  :deploy-repositories {"releases" :clojars})

;;
