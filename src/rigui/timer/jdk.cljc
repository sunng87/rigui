(ns rigui.timer.jdk
  (:require [rigui.utils :refer [now]])
  (:import [java.util.concurrent Delayed DelayQueue TimeUnit]))

(defrecord JdkDelayQueueTimer [running queue master-thread])
(defrecord JdkDelayedTask [value expiry]
  Delayed
  (getDelay [_ unit]
    (.convert unit (- expiry (now)) TimeUnit/NANOSECONDS))
  (compareTo [_ o]
    (compare expiry (.expiry o))))

(defn start-timer [handler-fn]
  (let [queue (DelayQueue.)
        running (atom true)
        master-dispatcher (fn []
                            (while @running
                              (try (let [task (.take queue)]
                                     (handler-fn (.value task)))
                                   (catch Exception e (.printStackTrace e)))))
        master-thread (doto (Thread. master-dispatcher "rigui-jdk-timer-thread")
                        (.setDaemon true)
                        (.start))]
    (JdkDelayQueueTimer. running queue master-thread)))

(defn schedule! [timer value delay]
  (.offer (.queue timer) (JdkDelayedTask. value (+ (now) delay))))

(defn stop-timer! [timer]
  (reset! (.running timer) false))
