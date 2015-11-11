(ns rigui.core
  (:require [rigui.units :as unit]
            [rigui.impl :as impl]))

(def start impl/start)
(def schedule! impl/schedule!)
(def stop impl/stop)

(extend-protocol unit/Convert
  java.lang.Long
  (unit/to-millis [this] (unit/to-millis (unit/millis (.longValue this))))
  (unit/to-nanos [this] (unit/to-nanos (unit/millis (.longValue this))))

  java.lang.Integer
  (unit/to-millis [this] (unit/to-millis (unit/millis (.longValue this))))
  (unit/to-nanos [this] (unit/to-nanos (unit/millis (.longValue this)))))
