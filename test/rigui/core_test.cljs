(ns rigui.core-test
  (:require [cljs.test :refer-macros [deftest is testing] :as t]
            [rigui.core :refer [start schedule! cancel! stop]]))

(deftest test-scheduler
  (let [task-count 10000
        task-time 2500
        task-counter (atom task-count)
        tws (start 1 8 (fn [_] (swap! task-counter dec)))
        verifier-tws (start 1 12 (fn [_]
                                   (is (= (count (stop tws)) 0))
                                   (is (= @task-counter 0))))]
    (time
     (dotimes [_ task-count]
       (schedule! tws nil (rand-int task-time))))
    (schedule! verifier-tws nil task-time)))

(deftest test-cancel
  (let [tw (start 1 8 (fn [_] (is false)))
        task (schedule! tw nil 2000)]
    (cancel! tw task)
    (is (= 0 (count (stop tw))))
    (is true)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Entry Point
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(enable-console-print!)
(set! *main-cli-fn* #(t/run-tests))
(defmethod t/report [:cljs.test/default :end-run-tests]
  [m]
  (if (t/successful? m)
    (set! (.-exitCode js/process) 0)
    (set! (.-exitCode js/process) 1)))
