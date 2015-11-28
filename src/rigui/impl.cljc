(ns rigui.impl
  (:require [rigui.units :as unit]
            [rigui.math :as math]))

(def ^:dynamic *dry-run* false)

(defn- core-count []
  #?(:clj (.availableProcessors (Runtime/getRuntime))))

(defonce ^{:private true} wheel-scheduler
  #?(:clj (java.util.concurrent.Executors/newScheduledThreadPool (core-count))))

(defrecord TimingWheel [future buckets last-rotate])
;; TODO: mark for stopped, donot accept new task
(defrecord TimingWheels [wheels tick bucket-count consumer])
(defrecord Task [task delay created-on cancelled?])

(defn- now []
  #?(:clj (System/nanoTime)))

(defn new-bucket [] (ref #{}))

(defn- rotate-wheel [buckets]
  (conj (subvec buckets 1) (new-bucket)))

(defn bookkeeping [^TimingWheels parent wheel-level]
  (let [^TimingWheel wheel (nth @(.wheels parent) wheel-level)
        bucket @(dosync
                 (let [b (first @(.buckets wheel))]
                   (alter (.buckets wheel) rotate-wheel)
                   (ref-set (.last-rotate wheel) (now))
                   b))]

    (if (= wheel-level 0)
      ;; TODO: catch InterruptException and return unexecuted tasks
      (doseq [^Task t bucket]
        (when-not @(.cancelled? t)
          ;; enqueue to executor takes about 0.001ms to executor
          ((.consumer parent) (.task t))))
      (dosync
       ;; TODO: STM scope (dosync (doseq ...)) or (doseq (dosync ...))
       (doseq [^Task t bucket]
         (let [d (.delay t) ;; fixme: recalc delay
               next-wheel-delay (mod d (* (.tick parent)
                                          (math/pow (.bucket-count parent)
                                                    wheel-level)))
               next-bucket-index (quot next-wheel-delay
                                       (* (.tick parent)
                                          (math/pow (.bucket-count parent)
                                                    (dec wheel-level))))
               ^TimingWheel next-wheel (nth @(.wheels parent) (dec wheel-level))]
           (alter (nth @(.buckets next-wheel) next-bucket-index) conj t)))))))

(defn create-wheel [^TimingWheels parent level]
  (let [buckets (ref (mapv (fn [_] (new-bucket)) (range (.bucket-count parent))))
        schedule-future (agent nil)]
    (when-not *dry-run*
      (send schedule-future
            (fn [_] #?(:clj (.scheduleAtFixedRate ^java.util.concurrent.ScheduledExecutorService wheel-scheduler
                                                    (partial bookkeeping parent level)
                                                    0
                                                    (* (.tick parent) (Math/pow (.bucket-count parent) level))
                                                    java.util.concurrent.TimeUnit/NANOSECONDS)))))
    (TimingWheel. schedule-future buckets (ref (now)))))

(defn level-and-bucket-for-delay [delay ^TimingWheels tws]
  (if (= 0 delay)
    ;; 0 delay, return -1 level to execute at once
    [-1 nil]
    (let [tick (.tick tws)
          bucket-count (.bucket-count tws)
          level (int (math/floor (/ (math/log (/ delay tick)) (math/log bucket-count))))
          wheel (nth (.wheels tws) level)
          relative-delay (- delay (- now (.last-rotate wheel)))
          bucket (int (/ relative-delay (* (math/pow bucket-count level) tick)))]
      [level bucket])))

(defn start [tick bucket-count consumer]
  (TimingWheels. (ref []) (unit/to-nanos tick) bucket-count consumer))

(defn schedule! [^TimingWheels tw task delay]
  (let [delay (unit/to-nanos delay)
        [level bucket] (level-and-bucket-for-delay delay tw)
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
             ^TimingWheel the-wheel (nth wheels level)]
         (alter (nth @(.buckets the-wheel) bucket) conj task-entity))))
    task-entity))

(defn stop [^TimingWheels tw]
  (doseq [wheel @(.wheels tw)]
    (send (.future wheel) (fn [fu] #?(:clj (.cancel ^java.util.concurrent.Future fu true)))))
  (mapcat (fn [w] (mapcat (fn [b] (map #(.task ^Task %) @b)) @(.buckets w))) @(.wheels tw)))

(defn cancel! [tw task]
  (when-not @(.cancelled? task)
    (reset! (.cancelled? task) true)
    (let [delay (- (.delay task) (- (now) (.created-on task)))
          [level bucket-index] (level-and-bucket-for-delay delay tw)
          bucket (nth @(.buckets (nth @(.wheels tw) level)) bucket-index)]
      (dosync
       (alter bucket disj task))))
  task)

(defn pendings [^TimingWheels tw]
  (->> @(.wheels tw)
       (mapcat #(deref (.buckets ^TimingWheel %)))
       (reduce #(+ %1 (count @%2)) 0)))
