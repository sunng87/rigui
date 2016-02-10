(ns rigui.utils)

(defn now []
  #?(:clj (System/nanoTime)))

(defmacro safely [& body]
  `(try
     ~@body
     (catch Throwable e#
       (.printStackTrace e#))))
