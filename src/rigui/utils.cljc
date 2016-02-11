(ns rigui.utils)

(defn now []
  #?(:clj (System/nanoTime)
     :cljs (.getTime (js/Date.))))
