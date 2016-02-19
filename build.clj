;; copied from promesa: https://github.com/funcool/promesa
(require '[cljs.build.api :as b])

(println "Building ...")

(let [start (System/nanoTime)]
  (b/build
   (b/inputs "test" "src")
   {:main 'rigui.core-test
    :output-to "target/js/test/tests.js"
    :output-dir "target/js/test"
    :target :nodejs
    :pretty-print true
    :optimizations :none
    :language-in  :ecmascript5
    :language-out :ecmascript5
    :verbose true})
  (println "... done. Elapsed" (/ (- (System/nanoTime) start) 1e9) "seconds"))
