(ns rigui.core-test
  (:require [clojure.test :refer :all]
            [rigui.core :refer :all])
  (:import [java.util.concurrent Executors TimeUnit]))

(deftest test-scheduler
  (let [task-count 10000
        task-time 2500
        task-counter (atom task-count)
        executor (Executors/newFixedThreadPool (.availableProcessors (Runtime/getRuntime)))
        tws (start 1 8 (fn [_] (swap! task-counter dec)) executor)]
    (time
     (dotimes [_ task-count]
       (schedule! tws nil (rand-int task-time))))
    (Thread/sleep (* 1.1 task-time))
    (is (= (count (stop tws)) 0))))

(deftest test-cancel
  (let [tw (start 1 8 (fn [_] (is false)))
        task (schedule! tw nil 2000)]
    (cancel! tw task)
    (is (= 0 (count (stop tw))))
    (is true)))
