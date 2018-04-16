(ns rigui.timer.virtual
  (:require [rigui.timer.protocol :as p]))

(defrecord VirtualTimer [current-time tasks handler-fn])

(defn start-timer [handler-fn]
  (let [current-time (atom 0)
        task-set (atom (sorted-set-by :time))]
    (VirtualTimer. current-time task-set handler-fn)))

(extend-protocol p/TimerProtocol
  VirtualTimer

  (schedule! [this value delay]
    (swap! (.-task-set this) conj {:value value
                                    :delay delay}))

  (stop-timer! [this]))

(defn set-time! [^VirtualTimer timer time]
  (let [{tasks-to-trigger true
         tasks-to-retain false} (group-by #(<= (:time %) time) @(.-task-set timer))]
    (dorun (map #((.-handler-fn timer) (:value %)) tasks-to-trigger))
    (reset! (.-task-set timer) (apply sorted-set-by :time tasks-to-retain))
    (swap! (.-current-time timer) time)))

(def ^:dynamic *using-virtual-timer* false)

(defmacro with-virtual-timer [& body]
  `(with-binding [*using-virtual-timer* true]
     ~@body))
