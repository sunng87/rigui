(ns rigui.async-test
  (:require [rigui.async :as sut]
            #?(:clj [clojure.test :as t :refer :all]
               :cljs [cljs.test :as t :refer-macros [deftest is testing]])
            #?(:clj [clojure.core.async :as async :refer [go]]
               :cljs [cljs.core.async :as async]))
  #?(:cljs (:require-macros [cljs.core.async.macros :refer [go]])))

(deftest test-delayed-chan
  (testing "base case"
    (let [d-chan (sut/delayed-chan)
          thing (sut/delayed-value :a 300)]
      (go
        (async/>! d-chan thing))
      #?(:clj (async/<!!
               (go
                 (let [t (async/timeout 100)
                       [_ port] (async/alts! [t d-chan])]
                   (is (= port t)))
                 (let [t2 (async/timeout 210)
                       [v port] (async/alts! [t2 d-chan])]
                   (is (= port d-chan))
                   (is (= v :a)))))
         :cljs (go
                 (let [t (async/timeout 100)
                       [_ port] (async/alts! [t d-chan])]
                   (is (= port t)))
                 (let [t2 (async/timeout 310)
                       [v port] (async/alts! [t2 d-chan])]
                   (is (= port d-chan))
                   (is (= v :a))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Entry Point
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
#?(:cljs (do
           (enable-console-print!)
           (set! *main-cli-fn* #(t/run-tests))
           (defmethod t/report [:cljs.test/default :end-run-tests]
             [m]
             (if (t/successful? m)
               (set! (.-exitCode js/process) 0)
               (set! (.-exitCode js/process) 1)))))
