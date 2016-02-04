(ns rigui.impl-test
  (:require [rigui.impl :refer :all]
            [rigui.timer.jdk :refer [*dry-run*]]
            [rigui.units :refer :all]
            [rigui.math :as math]
            [rigui.utils :refer [now]]
            [clojure.test :refer :all])
  (:import [java.util.concurrent Executors TimeUnit]))

(defn wheel-tick [tick bucket-len level]
  (* (math/pow bucket-len level) tick))

(deftest test-level-and-bucket-calc
  (binding [*dry-run* true]
    (let [bucket-per-wheel 8
          tick (to-nanos (millis 10))
          wheel-last-rotate (now)]
      (are [x y] (= (let [d (to-nanos (millis x))
                          level (level-for-delay d tick bucket-per-wheel)]
                      [level (bucket-index-for-delay d level (wheel-tick tick bucket-per-wheel level)
                                                     bucket-per-wheel wheel-last-rotate)]) y)
        ;; at once
        0 [-1 -1]
        ;; less than a tick, executed at once
        5 [-1 -1]
        ;; in the first wheel
        11 [0 1]
        ;; higher level of wheel
        230 [1 2]
        ;; even higher level of wheel
        3201 [2 5]))))

(defn bucket-at [tws wheel-index bucket-index]
  (nth @(.buckets (nth @(.wheels tws) wheel-index)) bucket-index))

(deftest test-wheel
  (let [mark (atom false)
        tws (start (millis 10) 8 (fn [_] (reset! mark true)))]
    (binding [*dry-run* true]
      ;; init
      (schedule! tws 10 (millis 1000))
      (is (= 3 (count @(.wheels tws))))
      (is (every? #(empty? @%) @(.buckets (nth @(.wheels tws) 0))))
      (is (every? #(empty? @%) @(.buckets (nth @(.wheels tws) 1))))
      (is (empty? @(bucket-at tws 2 0)))
      (is (= 1 (count @(bucket-at tws 2 1))))
      (is (empty? @(bucket-at tws 2 2)))

      ;; rotate
      (book-keeping [tws 2])
      (is (= 3 (count @(.wheels tws))))
      (is (every? #(empty? @%) @(.buckets (nth @(.wheels tws) 0))))
      (is (every? #(empty? @%) @(.buckets (nth @(.wheels tws) 1))))
      (is (= 1 (count @(bucket-at tws 2 0))))
      (is (empty? @(bucket-at tws 2 1)))
      (is (empty? @(bucket-at tws 2 2)))

      ;; rotate again
      (book-keeping [tws 2])
      (is (every? #(empty? @%) @(.buckets (nth @(.wheels tws) 0))))
      (is (= 1 (count @(bucket-at tws 1 4))))
      (is (every? #(empty? @%) @(.buckets (nth @(.wheels tws) 2))))

      ;; let wheel 1 rotate
      (dotimes [_ 5] (book-keeping [tws 1])) ;;4->0
      (is (= 1 (count @(bucket-at tws 0 4))))
      (is (every? #(empty? @%) @(.buckets (nth @(.wheels tws) 1))))
      (is (every? #(empty? @%) @(.buckets (nth @(.wheels tws) 2))))

      ;; let wheel 0 rotate
      (dotimes [_ 4] (book-keeping [tws 0]))
      (is (= 1 (count @(bucket-at tws 0 0))))
      (is (not @mark))

      ;; last rotation
      (book-keeping [tws 0])
      (is (every? #(empty? @%) @(.buckets (nth @(.wheels tws) 0))))
      (is @mark))))

(deftest test-cancel
  (binding [*dry-run* true]
    (let [tws (start (seconds 10) 8 (constantly true))
          task (schedule! tws "value" (seconds 75))]
      (is (some #(not-empty @%) @(.buckets (nth @(.wheels tws) 0))))
      ;;
      (cancel! tws task)
      (is @(.cancelled? task))

      (is (every? #(empty? @%) @(.buckets (nth @(.wheels tws) 0)))))))

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
