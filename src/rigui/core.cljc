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
  "Start a timer and return it.
  * tick: the tick size of timing wheel
  * bucket-count: buckets per wheel
  * consumer-fn: the function will be called when the value emitted, and result will be delivered to task promise. Default function is `identity`
  * executor: the executor that consumer-fn runs on (only available to JVM hosted clojure). By default consumer function will run on timer thread."
  ([tick bucket-count]
   (impl/start (convert-unit tick) bucket-count identity (u/now)))
  ([tick bucket-count consumer-fn]
   (impl/start (convert-unit tick) bucket-count consumer-fn (u/now)))
  #?(:clj ([tick bucket-count consumer-fn ^ExecutorService executor]
           (if executor
             (start tick bucket-count
                    (fn [v] (.submit executor ^Runnable (cast Runnable #(consumer-fn v)))))
             (start tick bucket-count consumer-fn)))))

(defn ^:export later!
  "Schedule some value to be executed later, returns a promise-like object holds the result. "
  [^TimingWheels tw value delay]
  (let [delay (convert-unit delay)]
    (impl/schedule-value! tw value delay (u/now) nil)))

(defn ^:export every!
  "Schedule some value to be executed at a fixed interval."
  [^TimingWheels tw value delay interval]
  (let [delay (convert-unit delay)
        interval (convert-unit interval)]
    (impl/schedule-value! tw value delay (u/now) interval)))

(defn ^:export cancel!
  "Cancel a task."
  [^TimingWheels tw ^Task t]
  (impl/cancel-task! t tw (u/now)))

(defn ^:export stop
  "Stop the timer."
  [^TimingWheels tw]
  (impl/stop tw))
