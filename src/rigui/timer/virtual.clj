(ns rigui.timer.virtual)

(defrecord VirtualTimer [current-time tasks handler-fn])

(defn start-timer [handler-fn]
  (let [current-time (atom 0)
        task-set (atom (sorted-set-by :time))]
    (VirtualTimer. current-time task-set handler-fn)))

(defn stop-timer! [timer]
  ;; do nothing
  )

(defn schedule! [^VirtualTimer timer value delay]
  (swap! (.-task-set timer) conj {:value value
                                  :delay delay}))

(defn set-time! [^VirtualTimer timer time]
  (let [{tasks-to-trigger true
         tasks-to-retain false} (group-by #(<= (:time %) time) @(.-task-set timer))]
    (dorun (map #((.-handler-fn timer) (:value %)) tasks-to-trigger))
    (reset! (.-task-set timer) (apply sorted-set-by :time tasks-to-retain))
    (swap! (.-current-time timer) time)))
