(ns rigui.impl-test
  (:require [rigui.impl :refer :all]
            [rigui.units :refer :all]
            [clojure.test :refer :all]))

(deftest test-level-and-bucket-calc
  (let [bucket-per-wheel 8
        tick 10]
    (are [x y] (= (level-and-bucket-for-delay x tick bucket-per-wheel) y)
      ;; less than a tick, executed at once
      5 [-1 4]
      ;; in the first wheel
      11 [0 1]
      ;; higher level of wheel
      230 [1 2]
      ;; even higher level of wheel
      3201 [2 5])))

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
      (bookkeeping tws 2)
      (is (= 3 (count @(.wheels tws))))
      (is (every? #(empty? @%) @(.buckets (nth @(.wheels tws) 0))))
      (is (every? #(empty? @%) @(.buckets (nth @(.wheels tws) 1))))
      (is (= 1 (count @(bucket-at tws 2 0))))
      (is (empty? @(bucket-at tws 2 1)))
      (is (empty? @(bucket-at tws 2 2)))

      ;; rotate again
      (bookkeeping tws 2)
      (is (every? #(empty? @%) @(.buckets (nth @(.wheels tws) 0))))
      (is (= 1 (count @(bucket-at tws 1 4))))
      (is (every? #(empty? @%) @(.buckets (nth @(.wheels tws) 2))))

      ;; let wheel 1 rotate
      (dotimes [_ 5] (bookkeeping tws 1)) ;;4->0
      (is (= 1 (count @(bucket-at tws 0 4))))
      (is (every? #(empty? @%) @(.buckets (nth @(.wheels tws) 1))))
      (is (every? #(empty? @%) @(.buckets (nth @(.wheels tws) 2))))

      ;; let wheel 0 rotate
      (dotimes [_ 4] (bookkeeping tws 0))
      (is (= 1 (count @(bucket-at tws 0 0))))
      (is (not @mark))

      ;; last rotation
      (bookkeeping tws 0)
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
