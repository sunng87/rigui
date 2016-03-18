(ns rigui.bench.jdk
  (:require [rigui.core :as rigui])
  (:import [java.util.concurrent ScheduledThreadPoolExecutor Executors
            TimeUnit CountDownLatch]))

(defn cpus []
  (.availableProcessors (Runtime/getRuntime)))

(defn cpu-intensive-pool []
  (Executors/newFixedThreadPool (cpus)))

(defn now [] (System/currentTimeMillis))

(defn throughput [size]
  (let [delay 5099
        jvm-latch (CountDownLatch. size)
        jvm-timer (ScheduledThreadPoolExecutor. (cpus))
        jvm-timer-error-acc (atom 0)

        rigui-latch (CountDownLatch. size)
        rigui-timer-error-acc (atom 0)
        rigui-timer (rigui/start 1 8
                                 #(do
                                    (swap! rigui-timer-error-acc +
                                           (Math/abs (- (now) (+ delay %))))
                                    (.countDown rigui-latch))
                                 (cpu-intensive-pool))]
    (time (dorun
           (pmap (fn [_]
                   (let [cur (now)]
                     (.schedule ^ScheduledThreadPoolExecutor jvm-timer
                              ^Runnable (fn []
                                          (let [err (Math/abs (- (now) (+ delay cur)))]
                                            (swap! jvm-timer-error-acc + err))
                                          (.countDown jvm-latch))
                              ^Long (long delay)
                              ^TimeUnit TimeUnit/MILLISECONDS)))
                 (range size))))
    (time (dorun
           (pmap (fn [_]
                   (rigui/later! rigui-timer (now) delay))
                 (range size))))
    (.await jvm-latch)
    (.await rigui-latch)
    (println "jvm error:" (/ (double @jvm-timer-error-acc) size))
    (println "rigui error:" (/ (double @rigui-timer-error-acc) size))))
