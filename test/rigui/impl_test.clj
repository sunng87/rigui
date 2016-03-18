(ns rigui.impl-test
  (:require [rigui.impl :refer :all]
            [rigui.timer.platform :refer [*dry-run*]]
            [rigui.units :refer :all]
            [rigui.math :as math]
            [rigui.utils :refer [now]]
            [clojure.test :refer :all])
  (:import [clojure.lang ExceptionInfo]))

(deftest test-level-for-target
  (testing "level -1"
    (is (= -1 (level-for-target 1 0 2 10)))
    (is (= -1 (level-for-target -1 0 2 10))))
  (testing "normal level computing"
    (is (= 0 (level-for-target 5 0 1 8)))
    (is (= 1 (level-for-target 10 0 1 8)))
    (is (= 2 (level-for-target 70 0 1 8)))))

(deftest test-bucket-index-for-target
  (is (= 5 (bucket-index-for-target 5 1 0)))
  (is (= 8 (bucket-index-for-target 9 8 0)))
  (is (= 16 (bucket-index-for-target 18 8 0)))
  (is (= 17 (bucket-index-for-target 19 8 1))))

(deftest test-create-bucket
  (let [t 1
        bc 8
        tw (start t bc identity 0)]
    (dosync
     (binding [*dry-run* true]
       (create-bucket tw 2 64 0)
       (create-bucket tw 2 192 0)))
    (is (= 2 (count @(.buckets tw))))
    (is (some? @(get @(.buckets tw) 64)))))

(deftest test-schedule-value
  (binding [*dry-run* true]
    (let [tw (start 1 8 identity 0)]
      (schedule-value! tw :a 10 0 nil)
      (schedule-value! tw :b 100 0 nil)
      (schedule-value! tw :c 3 0 nil)
      (schedule-value! tw :d 128 0 nil)

      (is (= :a (-> tw
                    (.buckets)
                    (deref)
                    (get 8) ;; computed trigger time
                    (deref)
                    (first)
                    (.value))))
      (is (= :b (-> tw
                    (.buckets)
                    (deref)
                    (get 64)
                    (deref)
                    (first)
                    (.value))))
      (println (-> tw
                   (.buckets)
                   (deref)
                   (get 64)
                   (deref)
                   (first)))
      (is (= :c (-> tw
                    (.buckets)
                    (deref)
                    (get 3)
                    (deref)
                    (first)
                    (.value))))
      (is (= :d (-> tw
                    (.buckets)
                    (deref)
                    (get 128)
                    (deref)
                    (first)
                    (.value)))))))


(deftest test-cancel-task
  (binding [*dry-run* true]
    (let [tw (start 1 8 identity 0)
          task (schedule-value! tw :a 10 0 nil)]
      (cancel! task tw 0)
      (is @(.cancelled? task))

      (is (empty? (-> tw
                      (.buckets)
                      (deref)
                      (get 8)
                      (deref)))))))

(deftest test-stop-tw
  (binding [*dry-run* true]
    (let [tw (start 1 8 identity 0)]
      (schedule-value! tw :a 10 0 nil)
      (schedule-value! tw :b 100 0 nil)

      (let [remains (stop tw)]
        (await (.running tw))
        (is (false? @(.running tw)))
        (is (= 2 (count remains)))
        (is (every? #{:a :b} remains)))

      (try
        (schedule-value! tw :c 10 0 nil)
        (is false)
        (catch ExceptionInfo e
          (is (= :rigui.impl/timer-stopped (:reason (ex-data e)))))))))
