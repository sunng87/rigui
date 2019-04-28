(ns rigui.utils
  #?(:clj (:import [java.time Clock Instant])))

(def clock (Clock/systemDefaultZone))

(defn now []
  #?(:clj (let [inst (Instant/now clock)]
            (+ (* 1000000000 (.getEpochSecond inst)) (.getNano inst)))
     :cljs (.getTime (js/Date.))))
