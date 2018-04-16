(ns rigui.timer.platform
  (:require [rigui.timer.protocol :as p]))

(def ^:dynamic *dry-run* false)

(defrecord JsTimer [running handler])

(defn start-timer [handler-fn]
  (JsTimer. (atom true) handler-fn))

(extend-protocol p/TimerProtocol
  JsTimer
  (schedule! [this value delay]
    (when (and (not *dry-run*) @(.-running this))
      (js/setTimeout #((.-handler this) value) delay)))
  (stop-timer! [this]
    (reset! (.-running this) false)))
