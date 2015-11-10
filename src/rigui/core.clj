(ns rigui.core
  (:import [java.util.concurrent Executors ScheduledExecutorService TimeUnit]))

(defn- core-count []
  (.availableProcessors (Runtime/getRuntime)))

(defonce wheel-scheduler
  (Executors/newScheduledThreadPool (core-count)))

(defrecord TimingWheel [future buckets])
(defrecord TimingWheels [wheels tick bucket-count consumer])
(defrecord Task [task delay])

(defn create-timing-wheels [tick ^TimeUnit unit bucket-count consumer]
  (TimingWheels. (ref []) (.toNanos unit tick) bucket-count consumer))

(defn- rotate [buckets]
  (into [] (concat (rest buckets) [(first buckets)])))

(defn- tick-action [parent wheel-level]
  (let [wheel (nth @(.wheels parent) wheel-level)
        bucket (first @(.buckets wheel))]

    (if (= wheel-level 0)
      (do
        (doseq [t @bucket]
          ((.consumer parent) (.task t)))
        (dosync
         (ref-set bucket #{})
         (alter (.buckets wheel) rotate)))
      (dosync
       (doseq [t @bucket]
         (let [d (.delay t)
               next-wheel-delay (mod d (* (.tick parent)
                                          (Math/pow (.bucket-count parent)
                                                    wheel-level)))
               next-bucket-index (quot next-wheel-delay
                                       (* (.tick parent)
                                          (Math/pow (.bucket-count parent)
                                                    (dec wheel-level))))
               next-wheel (nth @(.wheels parent) (dec wheel-level))]
           (alter (nth @(.buckets next-wheel) next-bucket-index) conj t)))
       ;; rotate buckets
       (ref-set bucket #{})
       (alter (.buckets wheel) rotate)))))

(defn- create-wheel [parent level]
  (let [buckets (ref (map (fn [_] (ref #{})) (range (.bucket-count parent))))
        schedule-future (agent nil)]
    (send schedule-future (fn [_] (.scheduleWithFixedDelay ^ScheduledExecutorService wheel-scheduler
                                                          (partial tick-action parent level)
                                                          0
                                                          (* (.tick parent) (Math/pow (.bucket-count parent) level))
                                                          TimeUnit/NANOSECONDS)))
    (TimingWheel. schedule-future buckets)))

(defn level-and-bucket-for-delay [delay tick bucket-count]
  (let [level (int (Math/floor (/ (Math/log (/ delay tick)) (Math/log bucket-count))))
        bucket (int (/ delay (* (Math/pow bucket-count level) tick)))]
    [level bucket]))

(defn schedule! [tw task delay ^TimeUnit unit]
  (let [delay (.toNanos unit delay)
        [level bucket] (level-and-bucket-for-delay delay (.tick tw) (.bucket-count tw))]
    (if (< level 0)
      ((.consumer tw) task)
      (let [task-entity (Task. task delay)]
        (dosync
         (let [wheels (alter (.wheels tw) (fn [wheels]
                                            (let [current-wheel-count (count wheels)
                                                  levels-required (inc level)]
                                              (if (> levels-required current-wheel-count)
                                                (into [] (concat wheels (map #(create-wheel tw %)
                                                                             (range current-wheel-count levels-required))))
                                                wheels))))
               the-wheel (nth wheels level)]
           (alter (nth @(.buckets the-wheel) bucket) conj task-entity)))))))
