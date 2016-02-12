(ns rigui.core
  (:require [rigui.units :as unit]
            [rigui.impl :as impl]
            [rigui.utils :as u])
  (:import [rigui.impl TimingWheels Task]
           [java.util.concurrent ExecutorService]))

(extend-protocol unit/Convert
  java.lang.Long
  (unit/to-millis [this] (unit/to-millis (unit/millis (.longValue this))))
  (unit/to-nanos [this] (unit/to-nanos (unit/millis (.longValue this))))

  java.lang.Integer
  (unit/to-millis [this] (unit/to-millis (unit/millis (.longValue this))))
  (unit/to-nanos [this] (unit/to-nanos (unit/millis (.longValue this)))))

(defn start
  ([tick bucket-count consumer-fn]
   (impl/start #?(:clj (unit/to-nanos tick)) bucket-count consumer-fn (u/now)))
  ([tick bucket-count consumer-fn ^ExecutorService executor]
   (if executor
     (start tick bucket-count (fn [v] (.submit executor
                                               ^Runnable (cast Runnable #(consumer-fn v)))))
     (start tick bucket-count consumer-fn))))

(defn schedule! [^TimingWheels tw task delay]
  (let [delay #?(:clj (unit/to-nanos delay))]
    (impl/schedule-value! tw task delay (u/now))))

(defn cancel! [^Task task]
  (impl/cancel! task (u/now)))

(defn stop [^TimingWheels tw]
  (impl/stop tw))
