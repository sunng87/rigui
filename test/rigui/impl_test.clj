(ns rigui.impl-test
  (:require [rigui.impl :refer :all]
            [rigui.timer.jdk :refer [*dry-run*]]
            [rigui.units :refer :all]
            [rigui.math :as math]
            [rigui.utils :refer [now]]
            [clojure.test :refer :all])
  (:import [java.util.concurrent Executors TimeUnit]))

(deftest test-scheduler
  (let [task-count 10000
        task-time 2500
        task-counter (atom task-count)
        task-counter2 (atom 0)
        executor (Executors/newFixedThreadPool (.availableProcessors (Runtime/getRuntime)))
        tws (start (millis 1) 8 (fn [_] (.submit executor (cast Runnable (fn []
                                                                          (swap! task-counter2 inc)
                                                                          (swap! task-counter dec))))))]
    (time
     (dotimes [_ task-count]
       (schedule! tws nil (millis (rand-int task-time)))))
    (Thread/sleep (* 1.1 task-time))
    (is (= (pendings tws) 0))))
