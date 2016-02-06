(ns rigui.utils)

(defn now []
  #?(:clj (System/nanoTime)))
