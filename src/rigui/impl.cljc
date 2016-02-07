(ns rigui.impl
  (:require [rigui.units :as unit]
            [rigui.math :as math]
            [rigui.utils :refer [now]]
            [rigui.timer.jdk :as timer]))

(defrecord TimingWheel [buckets wheel-tick last-rotate])
;; TODO: mark for stopped, donot accept new task
(defrecord TimingWheels [wheels tick bucket-count timer consumer])
(defrecord Task [task delay created-on cancelled?])

(defn new-bucket []
  (ref #{}))

(defn- rotate-wheel [buckets]
  (conj (subvec buckets 1) (new-bucket)))

(defn level-for-delay [delay tick buckets-len]
  (if (>= 0 delay)
    -1
    (int (math/floor (/ (math/log (/ delay tick)) (math/log buckets-len))))))

(defn bucket-index-for-delay [delay level wheel-tick buckets-len wheel-last-rotate]
  (if (> 0 level)
    -1
    (let [relative-delay (if timer/*dry-run* delay
                             (let [current (now)
                                   wheel-last-rotate (or wheel-last-rotate current)]
                               (- delay (- (+ wheel-last-rotate wheel-tick) current))))
          computed-bucket (int (/ relative-delay wheel-tick))]
      (max 1 (min (dec buckets-len) computed-bucket)))))

(defn book-keeping [[^TimingWheels parent wheel-level]]
  (let [wheel (nth @(.wheels parent) wheel-level)]
    (let [bucket (dosync
                  (let [b (first (ensure (.buckets wheel)))]
                    (alter (.buckets wheel) rotate-wheel)
                    (ref-set (.last-rotate wheel) (now))
                    (ensure b)))]
      (when-not timer/*dry-run*
        (timer/schedule! (.timer parent) [parent wheel-level] (* (.wheel-tick wheel) (.bucket-count parent))))
      (if (= wheel-level 0)
        ;; TODO: catch InterruptException and return unexecuted tasks
        (doseq [^Task t bucket]
          (when-not @(.cancelled? t)
            ;; enqueue to executor takes about 0.001ms to executor
            ((.consumer parent) (.task t))))

        (dosync
         ;; TODO: STM scope (dosync (doseq ...)) or (doseq (dosync ...))
         (let [next-level (dec wheel-level)
               ^TimingWheel next-wheel (nth @(.wheels parent) next-level)]

           (doseq [^Task t bucket]
             (let [d (.delay t)
                   delay-remained (if timer/*dry-run*
                                    (mod (.delay t) (.wheel-tick wheel))
                                    (- (+ (.delay t) (.created-on t)) (now)))
                   next-bucket-index (bucket-index-for-delay delay-remained next-level
                                                             (.wheel-tick next-wheel)
                                                             (.bucket-count parent)
                                                             @(.last-rotate next-wheel))]
               (alter (nth @(.buckets next-wheel) next-bucket-index) conj t)))))))))

(defn create-wheel [^TimingWheels parent level]
  (let [buckets (ref (mapv (fn [_] (new-bucket)) (range (.bucket-count parent))))
        wheel-tick (* (.tick parent) (math/pow (.bucket-count parent) level))
        the-wheel (TimingWheel. buckets wheel-tick (ref nil))]
    (when-not timer/*dry-run*
      (dotimes [i (.bucket-count parent)]
        (timer/schedule! (.timer parent) [parent level] (* wheel-tick (inc i)))))
    the-wheel))

(defn start [tick bucket-count consumer]
  (TimingWheels. (ref []) (unit/to-nanos tick) bucket-count (timer/start-timer book-keeping) consumer))

(defn schedule! [^TimingWheels tw task delay]
  (let [delay (unit/to-nanos delay)
        level (level-for-delay delay (.tick tw) (.bucket-count tw))
        task-entity (Task. task delay (now) (atom false))]
    (if (< level 0)
      ((.consumer tw) task)
      (dosync
       (let [wheels (alter (.wheels tw) (fn [wheels]
                                          (let [current-wheel-count (count wheels)
                                                levels-required (inc level)]
                                            (if (> levels-required current-wheel-count)
                                              (into [] (concat wheels (map #(create-wheel tw %)
                                                                           (range current-wheel-count levels-required))))
                                              wheels))))
             ^TimingWheel the-wheel (nth wheels level)
             bucket-index (bucket-index-for-delay delay level (.wheel-tick the-wheel)
                                                  (.bucket-count tw) @(.last-rotate the-wheel))]
         (alter (nth @(.buckets the-wheel) bucket-index) conj task-entity))))
    task-entity))

(defn stop [^TimingWheels tw]
  (timer/stop-timer! (.timer tw))
  (mapcat (fn [w] (mapcat (fn [b] (map #(.task ^Task %) @b)) @(.buckets w))) @(.wheels tw)))

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
       (mapcat #(deref (.buckets ^TimingWheel %)))
       (reduce #(+ %1 (count (filter (fn [t] (not @(.cancelled? t))) @%2))) 0)))
