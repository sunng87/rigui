(ns rigui.math)

(defn pow [base p]
  #?(:clj (Math/pow base p)
     :cljs (.pow js/Math base p)))

(defn log [base]
  #?(:clj (Math/log base)
     :cljs (.log js/Math base)))

(defn floor [v]
  #?(:clj (Math/floor v)
     :cljs (.floor js/Math v)))
