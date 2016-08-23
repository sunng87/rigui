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
        jvm-timer-error (atom [])

        rigui-latch (CountDownLatch. size)
        rigui-timer-error (atom [])
        rigui-timer (rigui/start 1 8
                                 #(do
                                    (swap! rigui-timer-error conj
                                           (Math/abs (- (now) (+ delay %))))
                                    (.countDown rigui-latch))
                                 (cpu-intensive-pool))]

    (println "Testing enqueue time for JVM timer and Rigui")

    (println (format "Enqueue %s tasks into JVM timer" size))
    (time (dorun
           (pmap (fn [_]
                   (let [cur (now)]
                     (.schedule ^ScheduledThreadPoolExecutor jvm-timer
                              ^Runnable (fn []
                                          (let [err (Math/abs (- (now) (+ delay cur)))]
                                            (swap! jvm-timer-error conj err))
                                          (.countDown jvm-latch))
                              ^Long (long delay)
                              ^TimeUnit TimeUnit/MILLISECONDS)))
                 (range size))))

    (println)

    (println (format "Enqueue %s tasks into Rigui timer" size))
    (time (dorun
           (pmap (fn [_]
                   (rigui/later! rigui-timer (now) delay))
                 (range size))))
    (.await jvm-latch)
    (.await rigui-latch)

    (println)
    (println "Errors")
    (println "=====")

    (let [jvm-timer-error (sort @jvm-timer-error)]
      (println "JVM ScheduledThreadPoolExecutor error")
      (println "-----")
      (println "avg: " (/ (double (reduce + jvm-timer-error)) size))
      (println "max: " (last jvm-timer-error))
      (println "p99: " (nth jvm-timer-error (int (* 0.99 size))))
      (println "p95: " (nth jvm-timer-error (int (* 0.95 size))))
      (println "p75: " (nth jvm-timer-error (int (* 0.75 size))))
      (println "p50: " (nth jvm-timer-error (int (* 0.5 size)))))

    (println)

    (let [rigui-timer-error (sort @rigui-timer-error)]
      (println "Rigui error")
      (println "-----")
      (println "avg: " (/ (double (reduce + rigui-timer-error)) size))
      (println "max: " (last rigui-timer-error))
      (println "p99: " (nth rigui-timer-error (int (* 0.99 size))))
      (println "p95: " (nth rigui-timer-error (int (* 0.95 size))))
      (println "p75: " (nth rigui-timer-error (int (* 0.75 size))))
      (println "p50: " (nth rigui-timer-error (int (* 0.5 size)))))))
