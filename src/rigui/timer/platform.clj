(ns rigui.timer.platform
  (:require [rigui.utils :refer [now]])
  (:import [java.util.concurrent Delayed DelayQueue ExecutorService Executors TimeUnit]))

(def ^:dynamic *dry-run* false)

(defn- core-count []
  (.availableProcessors (Runtime/getRuntime)))

(defonce ^{:private true} worker-pool
  (Executors/newFixedThreadPool (core-count)))

(defrecord JdkDelayQueueTimer [running queue master-thread])
(defrecord JdkDelayedTask [value expiry]
  Delayed
  (getDelay [_ unit]
    (.convert unit (- expiry (now)) TimeUnit/NANOSECONDS))
  (compareTo [_ o]
    (if (instance? JdkDelayedTask o)
      (compare expiry (.-expiry ^JdkDelayedTask o))
      -1)))

(defn start-timer [handler-fn]
  (let [queue (DelayQueue.)
        running (atom true)
        master-dispatcher (fn []
                            (when-not *dry-run*
                              (while @running
                                (try (let [^JdkDelayedTask task (.take queue)]
                                       (.submit ^ExecutorService worker-pool
                                                ^Runnable (fn [] (handler-fn (.-value task)))))
                                     (catch Exception e (.printStackTrace e))))))
        master-thread (doto (Thread. master-dispatcher "rigui-jdk-timer-thread")
                        (.setDaemon true)
                        (.start))]
    (JdkDelayQueueTimer. running queue master-thread)))

(defn schedule! [^JdkDelayQueueTimer timer value delay]
  (when-not *dry-run* (.offer ^DelayQueue (.queue timer) (JdkDelayedTask. value (+ (now) delay)))))

(defn stop-timer! [^JdkDelayQueueTimer timer]
  (reset! (.-running timer) false))
