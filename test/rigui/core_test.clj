(ns rigui.core-test
  (:require [clojure.test :refer :all]
            [rigui.core :refer :all])
  (:import [java.util.concurrent Executors TimeUnit]))

(deftest test-scheduler
  (let [task-count 10000
        task-time 2500
        task-counter (atom task-count)
        executor (Executors/newFixedThreadPool (.availableProcessors (Runtime/getRuntime)))
        tws (start 1 8 (fn [_] (.submit executor (cast Runnable
                                                      (fn [] (swap! task-counter dec))))))]
    (time
     (dotimes [_ task-count]
       (schedule! tws nil (rand-int task-time))))
    (Thread/sleep (* 1.0 task-time))
    (is (= (count (stop tws)) 0))))
