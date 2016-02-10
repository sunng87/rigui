(ns rigui.impl-test
  (:require [rigui.impl :refer :all]
            [rigui.timer.jdk :refer [*dry-run*]]
            [rigui.units :refer :all]
            [rigui.math :as math]
            [rigui.utils :refer [now]]
            [clojure.test :refer :all]))

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

(deftest test-create-wheel
  (let [t 1
        bc 8
        tw (start t bc identity (now))]
    (dosync
     (dorun (map #(create-wheel tw %) (range 0 10))))
    (is (= 10 (count @(.wheels tw))))
    (doseq [i (range 0 10)]
      (is (= (* t (math/pow bc i)) (.wheel-tick (nth @(.wheels tw) i)))))))

(deftest test-create-bucket
  (let [t 1
        bc 8
        tw (start t bc identity 0)]
    (dosync
     (dorun (map #(create-wheel tw %) (range 0 10)))
     (binding [*dry-run* true]
       (create-bucket tw (nth (ensure (.wheels tw)) 2) 2 64 0)
       (create-bucket tw (nth (ensure (.wheels tw)) 2) 2 192 0)))
    (is (= 2 (count @(.buckets (nth @(.wheels tw) 2)))))
    (is (some? @(get @(.buckets (nth @(.wheels tw) 2)) 64)))))
