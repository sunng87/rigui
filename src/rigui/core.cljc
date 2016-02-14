(ns rigui.core
  (:require [rigui.units :as unit]
            [rigui.impl :as impl]
            [rigui.utils :as u])
  #?(:clj (:import [rigui.impl TimingWheels Task]
                   [java.util.concurrent ExecutorService])))

#?(:clj (extend-protocol unit/Convert
          java.lang.Long
          (to-millis [this] (unit/to-millis (unit/millis (long this))))
          (to-nanos [this] (unit/to-nanos (unit/millis (long this))))

          java.lang.Integer
          (to-millis [this] (unit/to-millis (unit/millis (long this))))
          (to-nanos [this] (unit/to-nanos (unit/millis (long this)))))
   :cljs (extend-protocol unit/Convert
           number
           (to-millis [this] (unit/to-millis (unit/millis this)))
           (to-nanos [this] (unit/to-nanos (unit/millis this)))))

(defn- convert-unit [u]
  #?(:clj (unit/to-nanos u)
     :cljs (unit/to-millis u)))

(defn ^:export start
  ([tick bucket-count consumer-fn]
   (impl/start (convert-unit tick) bucket-count consumer-fn (u/now)))
  #?(:clj ([tick bucket-count consumer-fn ^ExecutorService executor]
           (if executor
             (start tick bucket-count
                    (fn [v] (.submit executor ^Runnable (cast Runnable #(consumer-fn v)))))
             (start tick bucket-count consumer-fn)))))

(defn ^:export schedule! [^TimingWheels tw task delay]
  (let [delay (convert-unit delay)]
    (impl/schedule-value! tw task delay (u/now))))

(defn ^:export cancel! [^TimingWheels tw ^Task task]
  (impl/cancel! tw task (u/now)))

(defn ^:export stop [^TimingWheels tw]
  (impl/stop tw))
