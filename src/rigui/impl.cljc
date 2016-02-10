(ns rigui.impl
  (:require [rigui.units :as unit]
            [rigui.math :as math]
            [rigui.utils :refer [now]]
            [rigui.timer.jdk :as timer]))

(defrecord TimingWheel [buckets bucket-futures wheel-tick])
;; TODO: mark for stopped, donot accept new task
(defrecord TimingWheels [wheels tick bucket-count start-at timer consumer])
(defrecord Task [task target cancelled?])

(defn level-for-target [target current tick bucket-len]
  (let [delay (- target current)]
    (if (<= delay 0) -1
        (int (quot (math/log (/ delay tick)) (math/log bucket-len))))))

(defn bucket-index-for-target [target wheel-tick tw-start-at]
  (+ tw-start-at (* wheel-tick (quot (- target tw-start-at) wheel-tick))))

(defn create-wheel [^TimingWheels parent level]
  (let [buckets (ref {})
        bucket-futures (agent true)
        wheel-tick (* (.tick parent) (math/pow (.bucket-count parent) level))
        wheel (TimingWheel. buckets bucket-futures wheel-tick)]
    (alter (.wheels parent) conj wheel)))

(defn create-bucket [^TimingWheels parent ^TimingWheel wheel level trigger-time current-time]
  (alter (.buckets wheel) assoc trigger-time (ref #{}))
  (send (.bucket-futures wheel) (fn [running]
                                  (when running
                                    (timer/schedule! (.timer parent) [parent level trigger-time]
                                                     (- trigger-time current-time)))
                                  running)))

;; this function should be called with a dosync block
(defn schedule-task-on-wheels! [^TimingWheels parent ^Task task current]
  (let [level (level-for-target (.target task) current (.tick parent) (.bucket-count parent))
        current-levels (count (ensure (.wheels parent)))
        _ (when (> level (dec current-levels))
            (dorun (map #(create-wheel parent %) (range current-levels (inc level)))))
        wheel (nth (ensure (.wheels parent)) level)

        ;; aka bucket trigger-time
        bucket-index (bucket-index-for-target (.target task) (.wheel-tick wheel) (.start-at parent))

        _ (when (nil? (get (ensure (.buckets wheel)) bucket-index))
            (create-bucket parent wheel level bucket-index (now)))
        bucket (get (ensure (.buckets wheel)) bucket-index)]
    (alter bucket conj task)))

(defn book-keeping [[^TimingWheels parent wheel-level trigger-time]]
  (let [wheel (nth @(.wheels parent) wheel-level)]
    (let [bucket (dosync
                  (let [b (get (ensure (.buckets wheel)) trigger-time)]
                    (alter (.buckets wheel) dissoc trigger-time)
                    (ensure b)))]
      (if (= wheel-level 0)
        ;; TODO: catch InterruptException and return unexecuted tasks
        (doseq [^Task t bucket]
          (when-not @(.cancelled? t)
            ;; enqueue to executor takes about 0.001ms to executor
            ((.consumer parent) (.task t))))

        (doseq [^Task t bucket]
          (let [current (now)]
            (if (<= (- (.target t) current) (.tick parent))
             ((.consumer parent) (.task t))
             (dosync (schedule-task-on-wheels! parent t current)))))))))

(defn start [tick bucket-count consumer]
  (TimingWheels. (ref []) (unit/to-nanos tick) bucket-count (now)
                 (timer/start-timer book-keeping) consumer))

(defn schedule! [^TimingWheels tw task delay]
  (let [delay (unit/to-nanos delay)]
    (if (< delay (.tick tw))
      ((.consumer tw) task)
      (let [current (now)
            task-entity (Task. task (+ delay current) (atom false))]
        (dosync (schedule-task-on-wheels! tw task-entity current))))))

(defn stop [^TimingWheels tw]
  (timer/stop-timer! (.timer tw))
  (mapcat (fn [w] (mapcat (fn [b] (map #(.task ^Task %) @b)) (vals @(.buckets w)))) @(.wheels tw)))

(defn cancel! [^TimingWheels tw task]
  (when-not @(.cancelled? task)
    (reset! (.cancelled? task) true)
    (let [level (level-for-target (.target task) (now) (.tick tw) (.bucket-count tw))]
      (when (>= level 0)
        (when-let [wheel (nth @(.wheels tw) level)]
          (dosync
           (let [bucket-index (bucket-index-for-target (.target task) (.wheel-tick wheel)
                                                       (.start-at tw))]
             (alter (get (ensure (.buckets wheel)) bucket-index) disj task)))))))
  task)

(defn pendings [^TimingWheels tw]
  (->> @(.wheels tw)
       (mapcat #(vals (deref (.buckets ^TimingWheel %))))
       (reduce #(+ %1 (count (filter (fn [t] (not @(.cancelled? t))) @%2))) 0)))
