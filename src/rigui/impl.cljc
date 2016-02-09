(ns rigui.impl
  (:require [rigui.units :as unit]
            [rigui.math :as math]
            [rigui.utils :refer [now]]
            [rigui.timer.jdk :as timer]))

(defrecord TimingWheel [buckets bucket-futures wheel-tick])
;; TODO: mark for stopped, donot accept new task
(defrecord TimingWheels [wheels tick bucket-count start-at timer consumer])
(defrecord Task [task target cancelled?])

#_(defn- rotate-wheel [buckets]
  (conj (subvec buckets 1) (new-bucket)))

#_(defn level-for-delay [delay tick buckets-len]
  (if (>= 0 delay)
    -1
    (int (math/floor (/ (math/log (/ delay tick)) (math/log buckets-len))))))

(defn level-for-target [target current tick bucket-len]
  (let [delay (- target current)]
    (if (<= delay 0) -1
        (int (quot (math/log (/ delay tick)) (math/log bucket-len))))))

#_(defn bucket-index-for-delay [delay level wheel-tick buckets-len wheel-last-rotate]
  (if (> 0 level)
    -1
    (let [relative-delay (if timer/*dry-run* delay
                             (let [current (now)
                                   wheel-last-rotate (or wheel-last-rotate current)]
                               (- delay (- (+ wheel-last-rotate wheel-tick) current))))
          computed-bucket (int (/ relative-delay wheel-tick))]
      (max 1 (min (dec buckets-len) computed-bucket)))))

#_(defn bucket-index-for-delay [delay wheel-tick wheel-last-rotate]
  (let [relative-delay #break (if timer/*dry-run* delay
                           (- delay (- (+ wheel-last-rotate wheel-tick) (now))))]
    ;; wheel trigger time as bucket index
    (* wheel-tick (long (math/floor (/ (float relative-delay) wheel-tick))))))

(defn bucket-index-for-target [target wheel-tick tw-start-at]
  (+ tw-start-at (* wheel-tick (quot (- target tw-start-at) wheel-tick))))

(defn create-wheel [^TimingWheels parent level]
  (let [buckets (ref {})
        bucket-futures (agent true)
        wheel-tick (* (.tick parent) (math/pow (.bucket-count parent) level))
        wheel (TimingWheel. buckets bucket-futures wheel-tick)]
    (alter (.wheels parent) assoc-in level (constantly wheel))))

(defn create-bucket [^TimingWheels parent ^TimingWheel wheel level trigger-time current-time]
  (alter (.buckets wheel) assoc trigger-time [])
  (send (.bucket-futures wheel) (fn [running]
                                  (when running
                                    (timer/schedule! (.timer parent) [parent level trigger-time]
                                                     (- trigger-time current-time)))
                                  running)))

;; this function should be called with a dosync block
(defn schedule-task-on-wheels! [^TimingWheels parent ^Task task]
  (let [current (now)
        level (level-for-target (.target task) current (.tick parent) (.bucket-count parent))
        _ (when (nil? (get (ensure (.levels parent)) level))
            (create-wheel parent level))
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
                    (ref-set (.last-rotate wheel) (now))
                    (ensure b)))]
      (if (= wheel-level 0)
        ;; TODO: catch InterruptException and return unexecuted tasks
        (doseq [^Task t bucket]
          (when-not @(.cancelled? t)
            ;; enqueue to executor takes about 0.001ms to executor
            ((.consumer parent) (.task t))))

        (doseq [^Task t bucket] (dosync (schedule-task-on-wheels! parent t)))))))

(defn start [tick bucket-count consumer]
  (TimingWheels. (ref []) (unit/to-nanos tick) bucket-count (now)
                 (timer/start-timer book-keeping) consumer))

(defn schedule! [^TimingWheels tw task delay]
  (let [delay (unit/to-nanos delay)]
    (if (< delay (.tick tw))
      ((.consumer tw) task)
      (let [task-entity (Task. task (+ delay (now)) (atom false))]
        (schedule-task-on-wheels! tw task)))))

(defn stop [^TimingWheels tw]
  (timer/stop-timer! (.timer tw))
  (mapcat (fn [w] (mapcat (fn [b] (map #(.task ^Task %) @b)) (vals @(.buckets w)))) @(.wheels tw)))

;; FIXME: new buckets data structure
(defn cancel! [tw task]
  (when-not @(.cancelled? task)
    (reset! (.cancelled? task) true)
    (let [delay-remained (if timer/*dry-run* (.delay task) (- (+ (.delay task) (.created-on task)) (now)))
          level (level-for-delay delay-remained (.tick tw) (.bucket-count tw))
          wheel (nth @(.wheels tw) level)
          bucket-index (bucket-index-for-delay delay-remained level
                                               (.wheel-tick wheel) (.bucket-count tw)
                                               @(.last-rotate wheel))
          bucket (nth @(.buckets wheel) bucket-index)]
      (dosync
       (alter bucket disj task))))
  task)

(defn pendings [^TimingWheels tw]
  (->> @(.wheels tw)
       (mapcat #(vals (deref (.buckets ^TimingWheel %))))
       (reduce #(+ %1 (count (filter (fn [t] (not @(.cancelled? t))) @%2))) 0)))
