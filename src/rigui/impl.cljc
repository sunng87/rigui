(ns rigui.impl
  (:require [rigui.math :as math]
            [rigui.utils :refer [now]]
            [rigui.timer.jdk :as timer]))

(defrecord TimingWheel [buckets wheel-tick])
;; TODO: mark for stopped, donot accept new task
(defrecord TimingWheels [wheels tick bucket-count start-at timer consumer running])
(defrecord Task [value target cancelled?])

(defn level-for-target [target current tick bucket-len]
  (let [delay (- target current)]
    (if (<= delay tick) -1
        (int (quot (math/log (/ delay tick)) (math/log bucket-len))))))

(defn bucket-index-for-target [target wheel-tick tw-start-at]
  (+ tw-start-at (* wheel-tick (quot (- target tw-start-at) wheel-tick))))

(defn create-wheel [^TimingWheels parent level]
  (let [buckets (ref {})
        wheel-tick (* (.tick parent) (long (math/pow (.bucket-count parent) level)))
        wheel (TimingWheel. buckets wheel-tick)]
    (alter (.wheels parent) conj wheel)))

(defn create-bucket [^TimingWheels parent ^TimingWheel wheel level trigger-time current-time]
  (alter (.buckets wheel) assoc trigger-time (ref #{}))
  (send (.running parent) (fn [running]
                            (when running
                              (timer/schedule! (.timer parent) [parent level trigger-time]
                                               (- trigger-time current-time)))
                            running)))

;; this function should be called with a dosync block
(defn schedule-task-on-wheels! [^TimingWheels parent ^Task task current]
  (let [level (level-for-target (.target task) current (.tick parent)
                                (.bucket-count parent))
        current-levels (count (ensure (.wheels parent)))
        _ (when (> level (dec current-levels))
            (dorun (map #(create-wheel parent %) (range current-levels (inc level)))))
        wheel ^TimingWheel (nth (ensure (.wheels parent)) level)

        ;; aka bucket trigger-time
        bucket-index (bucket-index-for-target (.target task) (.wheel-tick wheel)
                                              (.start-at parent))

        _ (when (nil? (get (ensure (.buckets wheel)) bucket-index))
            (create-bucket parent wheel level bucket-index current))
        bucket (get (ensure (.buckets wheel)) bucket-index)]
    (alter bucket conj task)))

(defn book-keeping [[^TimingWheels parent wheel-level trigger-time]]
  (let [wheel ^TimingWheel (nth @(.wheels parent) wheel-level)]
    (let [bucket (dosync
                  (let [b (get (ensure (.buckets wheel)) trigger-time)]
                    (alter (.buckets wheel) dissoc trigger-time)
                    (ensure b)))]
      (if (= wheel-level 0)
        ;; TODO: catch InterruptException and return unexecuted tasks
        (doseq [^Task t bucket]
          (when-not @(.cancelled? t)
            ;; enqueue to executor takes about 0.001ms to executor
            ((.consumer parent) (.value t))))

        (doseq [^Task t bucket]
          (let [current (now)]
            (if (<= (- (.target t) current) (.tick parent))
              ((.consumer parent) (.value t))
              (dosync (schedule-task-on-wheels! parent t current)))))))))

(defn start [tick bucket-count consumer start-at]
  (TimingWheels. (ref []) tick bucket-count start-at
                 (timer/start-timer book-keeping) consumer
                 (agent true)))

(defn schedule-value! [^TimingWheels tw task delay current]
  (if @(.running tw)
    (if (<= delay (.tick tw))
      (do ((.consumer tw) task) nil)
      (let [task-entity (Task. task (+ current delay) (atom false))]
        (dosync (schedule-task-on-wheels! tw task-entity current))
        task-entity))
    (throw (IllegalStateException. "TimingWheels already stopped."))))

(defn stop [^TimingWheels tw]
  (send (.running tw) (constantly false))
  (timer/stop-timer! (.timer tw))
  (mapcat (fn [w] (mapcat (fn [b] (map #(.value ^Task %) @b))
                          (vals @(.buckets ^TimingWheel w))))
          @(.wheels tw)))

(defn cancel! [^TimingWheels tw ^Task task current]
  (when-not @(.cancelled? task)
    (reset! (.cancelled? task) true)
    (let [level (level-for-target (.target task) current (.tick tw) (.bucket-count tw))]
      (when (>= level 0)
        (when-let [wheel ^TimingWheel (nth @(.wheels tw) level)]
          (dosync
           (let [bucket-index (bucket-index-for-target (.target task) (.wheel-tick wheel)
                                                       (.start-at tw))]
             (when-let [bucket (get (ensure (.buckets wheel)) bucket-index)]
               (alter bucket disj task))))))))
  task)

(defn pendings [^TimingWheels tw]
  (->> @(.wheels tw)
       (mapcat #(vals (deref (.buckets ^TimingWheel %))))
       (reduce #(+ %1 (count (filter (fn [^Task t] (not @(.cancelled? t))) @%2))) 0)))
