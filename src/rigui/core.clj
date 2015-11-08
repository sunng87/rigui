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

(defn- dump-task [parent wheel-level]
  ;;TODO
  )

(defn- create-wheel [parent level]
  (let [buckets (repeat (.bucket-count parent) (ref #{}))
        schedule-future (agent nil)]
    (send schedule-future #(.scheduleWithFixedDelay ^ScheduledExecutorService wheel-scheduler
                                                     (partial dump-task parent level)
                                                     (.tick parent) TimeUnit/NANOSECONDS))
    (TimingWheel. schedule-future buckets)))

(defn level-and-bucket-for-delay [delay tick bucket-count]
  (let [level (int (Math/floor (/ (Math/log (/ delay tick)) (Math/log bucket-count))))
        bucket (int (/ delay (* (Math/pow bucket-count level) tick)))]
    [level bucket]))

(defn schedule! [tw task delay ^TimeUnit unit]
  (let [[level bucket] (level-and-bucket-for-delay delay (.tick tw) (.bucket-count tw))]
    (if (< level 0)
      ((.consumer tw) task)
      (let [task-entity (Task. task delay)]
        (dosync
         (let [wheels (alter (.wheels tw) (fn [wheels]
                                            (let [current-wheel-count (count wheels)]
                                              (if (> (- level current-wheel-count) 0)
                                                (concat wheels (map #(create-wheel tw %)
                                                                    (range current-wheel-count (inc level))))
                                                wheels))))
               the-wheel (nth wheels level)]
           (alter (nth (.buckets the-wheel) bucket) conj task-entity)))))))
