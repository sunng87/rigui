(ns rigui.impl
  (:require [rigui.math :as math]
            [rigui.utils :refer [now]]
            [rigui.timer.platform :as timer]))

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
  (let [buckets #?(:clj (ref {}) :cljs (atom {}))
        wheel-tick (* (.tick parent) (long (math/pow (.bucket-count parent) level)))
        wheel (TimingWheel. buckets wheel-tick)]
    #?(:clj (alter (.wheels parent) conj wheel)
       :cljs (swap! (.wheels parent) conj wheel))))

(defn create-bucket [^TimingWheels parent ^TimingWheel wheel level trigger-time current-time]
  #?@(:clj [(alter (.buckets wheel) assoc trigger-time (ref #{}))
            (send (.running parent) (fn [running]
                                      (when running
                                        (timer/schedule! (.timer parent) [parent level trigger-time]
                                                         (- trigger-time current-time)))
                                      running))]
      :cljs [(swap! (.buckets wheel) assoc trigger-time (atom #{}))
             (when @(.running parent)
               (timer/schedule! (.timer parent) [parent level trigger-time]
                                (- trigger-time current-time)))]))

;; this function should be called with a dosync block
(defn schedule-task-on-wheels! [^TimingWheels parent ^Task task current]
  (let [level (level-for-target (.target task) current (.tick parent)
                                (.bucket-count parent))
        current-levels (count #?(:clj (ensure (.wheels parent))
                                 :cljs @(.wheels parent)))
        _ (when (> level (dec current-levels))
            (dorun (map #(create-wheel parent %) (range current-levels (inc level)))))
        wheel ^TimingWheel (nth #?(:clj (ensure (.wheels parent))
                      :cljs @(.wheels parent))
                   level)

        ;; aka bucket trigger-time
        bucket-index (bucket-index-for-target (.target task) (.wheel-tick wheel)
                                              (.start-at parent))

        _ (when (nil? (get #?(:clj (ensure (.buckets wheel))
                              :cljs @(.buckets wheel))
                           bucket-index))
            (create-bucket parent wheel level bucket-index current))
        bucket (get #?(:clj (ensure (.buckets wheel))
                       :cljs @(.buckets wheel))
                    bucket-index)]
    #?(:clj (alter bucket conj task)
       :cljs (swap! bucket conj task))))

(defn book-keeping [[^TimingWheels parent wheel-level trigger-time]]
  (let [wheel ^TimingWheel (nth @(.wheels parent) wheel-level)]
    (let [bucket #?(:clj (dosync
                          (let [b (get (ensure (.buckets wheel)) trigger-time)]
                            (alter (.buckets wheel) dissoc trigger-time)
                            (ensure b)))
                    :cljs (let [b (get @(.buckets wheel) trigger-time)]
                            (swap! (.buckets wheel) dissoc trigger-time)
                            @b))]
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
              #?(:clj (dosync (schedule-task-on-wheels! parent t current))
                 :cljs (schedule-task-on-wheels! parent t current)))))))))

(defn start [tick bucket-count consumer start-at]
  (TimingWheels. #?(:clj (ref []) :cljs (atom []))
                 tick bucket-count start-at
                 (timer/start-timer book-keeping) consumer
                 #?(:clj (agent true) :cljs (atom true))))

(defn schedule-value! [^TimingWheels tw task delay current]
  (if @(.running tw)
    (if (<= delay (.tick tw))
      (do ((.consumer tw) task) nil)
      (let [task-entity (Task. task (+ current delay) (atom false))]
        #?(:clj (dosync (schedule-task-on-wheels! tw task-entity current))
           :cljs (schedule-task-on-wheels! tw task-entity current))
        task-entity))
    (throw #?(:clj (IllegalStateException. "TimingWheels already stopped.")
              :cljs (js/Error. "TimingWheels already stopped.")))))

(defn stop [^TimingWheels tw]
  #?(:clj (send (.running tw) (constantly false))
     :cljs (reset! (.running tw) false))
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
          #?(:clj (dosync
                   (let [bucket-index (bucket-index-for-target (.target task) (.wheel-tick wheel)
                                                               (.start-at tw))]
                     (when-let [bucket (get (ensure (.buckets wheel)) bucket-index)]
                       (alter bucket disj task))))
             :cljs (let [bucket-index (bucket-index-for-target (.target task) (.wheel-tick wheel)
                                                               (.start-at tw))]
                     (when-let [bucket (get @(.buckets wheel) bucket-index)]
                       (swap! bucket disj task))))))))
  task)

(defn pendings [^TimingWheels tw]
  (->> @(.wheels tw)
       (mapcat #(vals (deref (.buckets ^TimingWheel %))))
       (reduce #(+ %1 (count (filter (fn [^Task t] (not @(.cancelled? t))) @%2))) 0)))
