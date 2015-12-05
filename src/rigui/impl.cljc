(ns rigui.impl
  (:require [rigui.units :as unit]
            [rigui.math :as math]))

(def ^:dynamic *dry-run* false)

(defn- core-count []
  #?(:clj (.availableProcessors (Runtime/getRuntime))))

(defonce ^{:private true} wheel-scheduler
  #?(:clj (java.util.concurrent.Executors/newScheduledThreadPool (core-count))))

(defonce ^{:private true} bookkeeping-worker-pool
  #?(:clj (java.util.concurrent.Executors/newFixedThreadPool (core-count))))

(defrecord TimingWheel [future buckets last-rotate])
;; TODO: mark for stopped, donot accept new task
(defrecord TimingWheels [wheels tick bucket-count consumer])
(defrecord Task [task delay created-on cancelled?])

(defn now []
  #?(:clj (System/nanoTime)))

(defn new-bucket [] (ref #{}))

(defn- rotate-wheel [buckets]
  (conj (subvec buckets 1) (new-bucket)))

(defn level-for-delay [delay tick buckets-len]
  (if (>= 0 delay)
    -1
    (int (math/floor (/ (math/log (/ delay tick)) (math/log buckets-len))))))

(defn bucket-index-for-delay [delay level tick buckets-len wheel-last-rotate]
  (if (> 0 level)
    -1
    (let [wheel-tick (* (math/pow buckets-len level) tick)
          relative-delay (if *dry-run* delay
                             (let [current (now)
                                   wheel-last-rotate (or wheel-last-rotate current)]
                               (- delay (- (+ wheel-last-rotate wheel-tick) (now)))))
          computed-bucket (int (/ relative-delay wheel-tick))]
      (max 0 (min (dec buckets-len) computed-bucket)))))

(defn bookkeeping [^TimingWheels parent wheel-level]
  (dosync (ref-set (.last-rotate (nth @(.wheels parent) wheel-level)) (now)))
  (let [^TimingWheel wheel (nth @(.wheels parent) wheel-level)
        bucket (dosync
                (let [b (first @(.buckets wheel))]
                  (alter (.buckets wheel) rotate-wheel)
                  @b))]

    (.submit bookkeeping-worker-pool
             (fn []
               (try (if (= wheel-level 0)
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
                                 delay-remained (if *dry-run*
                                                  (mod (.delay t)
                                                       (* (.tick parent) (math/pow (.bucket-count parent) wheel-level)))
                                                  (- (+ (.delay t) (.created-on t)) (now)))
                                 next-bucket-index (bucket-index-for-delay delay-remained next-level
                                                                           (.tick parent) (.bucket-count parent)
                                                                           @(.last-rotate next-wheel))
                                 next-bucket-index (max 0 (min (dec (.bucket-count parent)) next-bucket-index))]
                             (alter (nth @(.buckets next-wheel) next-bucket-index) conj t)))))))))))

(defn create-wheel [^TimingWheels parent level]
  (let [buckets (ref (mapv (fn [_] (new-bucket)) (range (.bucket-count parent))))
        wheel-tick (* (.tick parent) (math/pow (.bucket-count parent) level))
        schedule-future (agent nil)]
    (when-not *dry-run*
      (send schedule-future
            (fn [_] #?(:clj (.scheduleAtFixedRate ^java.util.concurrent.ScheduledExecutorService wheel-scheduler
                                                    (partial bookkeeping parent level)
                                                    0
                                                    wheel-tick
                                                    java.util.concurrent.TimeUnit/NANOSECONDS)))))
    (TimingWheel. schedule-future buckets (ref nil))))

(defn start [tick bucket-count consumer]
  (TimingWheels. (ref []) (unit/to-nanos tick) bucket-count consumer))

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
             bucket-index (bucket-index-for-delay delay level (.tick tw) (.bucket-count tw)
                                                  @(.last-rotate the-wheel))]
         (alter (nth @(.buckets the-wheel) bucket-index) conj task-entity))))
    task-entity))

(defn stop [^TimingWheels tw]
  (doseq [wheel @(.wheels tw)]
    (send (.future wheel) (fn [fu] #?(:clj (.cancel ^java.util.concurrent.Future fu true)))))
  (mapcat (fn [w] (mapcat (fn [b] (map #(.task ^Task %) @b)) @(.buckets w))) @(.wheels tw)))

(defn cancel! [tw task]
  (when-not @(.cancelled? task)
    (reset! (.cancelled? task) true)
    (let [delay-remained (if *dry-run* (.delay task) (- (+ (.delay task) (.created-on task)) (now)))
          level (level-for-delay delay-remained (.tick tw) (.bucket-count tw))
          wheel (nth @(.wheels tw) level)
          bucket-index (bucket-index-for-delay delay-remained level
                                               (.tick tw) (.bucket-count tw)
                                               @(.last-rotate wheel))
          bucket (nth @(.buckets wheel) bucket-index)]
      (dosync
       (alter bucket disj task))))
  task)

(defn pendings [^TimingWheels tw]
  (->> @(.wheels tw)
       (mapcat #(deref (.buckets ^TimingWheel %)))
       (reduce #(+ %1 (count (filter (fn [t] (not @(.cancelled t))) @%2))) 0)))
