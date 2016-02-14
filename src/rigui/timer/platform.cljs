(ns rigui.timer.platform)

(def ^:dynamic *dry-run* false)

(defrecord JsTimer [running handler])

(defn start-timer [handler-fn]
  (JsTimer. (atom true) handler-fn))

(defn schedule! [timer value delay]
  (when (and (not *dry-run*) @(.running timer)))
  (js/setTimeout #((.handler timer) value) delay))

(defn stop-timer! [timer]
  (reset! (.running timer) false))
