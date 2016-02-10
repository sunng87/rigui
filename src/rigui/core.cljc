(ns rigui.core
  (:require [rigui.units :as unit]
            [rigui.impl :as impl]
            [rigui.utils :as u])
  (:import [rigui.impl TimingWheels]))

(extend-protocol unit/Convert
  java.lang.Long
  (unit/to-millis [this] (unit/to-millis (unit/millis (.longValue this))))
  (unit/to-nanos [this] (unit/to-nanos (unit/millis (.longValue this))))

  java.lang.Integer
  (unit/to-millis [this] (unit/to-millis (unit/millis (.longValue this))))
  (unit/to-nanos [this] (unit/to-nanos (unit/millis (.longValue this)))))

(defn start [tick bucket-count consumer-fn]
  (impl/start #?(:clj (unit/to-nanos tick)) bucket-count consumer-fn (u/now)))

(defn schedule! [^TimingWheels tw task delay]
  (let [delay #?(:clj (unit/to-nanos delay))]
    (impl/schedule-value! tw task delay (u/now))))

(defn cancel! [^TimingWheels tw task]
  (impl/cancel! tw task (u/now)))

(defn stop [^TimingWheels tw]
  (impl/stop tw))
