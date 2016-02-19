(ns rigui.impl
  (:require [rigui.math :as math]
            [rigui.utils :refer [now]]
            [rigui.timer.platform :as timer])
  #?(:clj (:import [clojure.lang IDeref IPending IBlockingDeref]
                   [java.io Writer])))

(defrecord TimingWheel [buckets wheel-tick])
(defrecord TimingWheels [wheels tick bucket-count start-at timer consumer running])

(defprotocol ITask
  (schedule! [this timer current])
  (execute! [this timer current])
  (cancel! [this timer current]))

(defrecord Task [value target cancelled? interval #?(:clj result-promise)]
  #?@(:clj
      [IDeref
       (deref [this] (deref result-promise))

       IBlockingDeref
       (deref [this timeout timeout-val]
              (deref result-promise timeout timeout-val))

       IPending
       (isRealized [this] (realized? result-promise))]))

#?(:clj
   (defmethod print-method Task [^Task t ^Writer w]
     (.write w
             (str "#rigui.task[{"
                  ":value " (pr-str (.-value t)) ", "
                  ":target " @(.-target t) ", "
                  (cond
                    (realized? t) (str ":result " (pr-str @t))
                    @(.-cancelled? t) ":status cancelled"
                    :else ":status pending")
                  "]}"))))

(defn level-for-target [target current tick bucket-len]
  (let [delay (- target current)]
    (if (<= delay tick) -1
        (int (quot (math/log (/ delay tick)) (math/log bucket-len))))))

(defn bucket-index-for-target [target wheel-tick tw-start-at]
  (+ tw-start-at (* wheel-tick (quot (- target tw-start-at) wheel-tick))))

(defn create-wheel [^TimingWheels parent level]
  (let [buckets #?(:clj (ref {}) :cljs (atom {}))
        wheel-tick (* (.-tick parent) (long (math/pow (.-bucket-count parent) level)))
        wheel (TimingWheel. buckets wheel-tick)]
    #?(:clj (alter (.-wheels parent) conj wheel)
       :cljs (swap! (.-wheels parent) conj wheel))))

(defn create-bucket [^TimingWheels parent ^TimingWheel wheel level trigger-time current-time]
  #?@(:clj [(alter (.-buckets wheel) assoc trigger-time (ref #{}))
            (send (.-running parent) (fn [running]
                                       (when running
                                         (timer/schedule! (.-timer parent) [parent level trigger-time]
                                                          (- trigger-time current-time)))
                                       running))]
      :cljs [(swap! (.-buckets wheel) assoc trigger-time (atom #{}))
             (when @(.-running parent)
               (timer/schedule! (.-timer parent) [parent level trigger-time]
                                (- trigger-time current-time)))]))

(defn book-keeping [[^TimingWheels parent wheel-level trigger-time]]
  (let [wheel ^TimingWheel (nth @(.-wheels parent) wheel-level)]
    (let [bucket #?(:clj (dosync
                          (let [b (get (ensure (.-buckets wheel)) trigger-time)]
                            (alter (.-buckets wheel) dissoc trigger-time)
                            (ensure b)))
                    :cljs (let [b (get @(.-buckets wheel) trigger-time)]
                            (swap! (.-buckets wheel) dissoc trigger-time)
                            @b))]
      (if (= wheel-level 0)
        (doseq [^Task t bucket]
          (let [current (now)]
            (execute! t parent current)))

        (doseq [^Task t bucket]
          (let [current (now)]
            (if (<= (- @(.-target t) current) (.-tick parent))
              (execute! t parent current)
              #?(:clj (dosync (schedule! t parent current))
                 :cljs (schedule! t parent current)))))))))

(defn start [tick bucket-count consumer start-at]
  (TimingWheels. #?(:clj (ref []) :cljs (atom []))
                 tick bucket-count start-at
                 (timer/start-timer book-keeping) consumer
                 #?(:clj (agent true) :cljs (atom true))))

(defn schedule-value! [^TimingWheels tw task delay current interval]
  (if @(.-running tw)
    (let [task-entity (Task. task (atom (+ current delay)) (atom false) interval
                             #?(:clj (promise)))]
      (if (and (some? interval) (<= interval (.-tick tw)))
        (throw (ex-info "Interval must longer than timer tick" {:reason ::invalid-interval}))
        (if (<= delay (.-tick tw))
          (execute! task-entity tw current)
          #?(:clj (dosync (schedule! task-entity tw current))
             :cljs (schedule! task-entity tw current))))
      task-entity)
    (throw (ex-info "TimingWheels already stopped." {:reason ::timer-stopped}))))

(defn stop [^TimingWheels tw]
  #?(:clj (send (.-running tw) (constantly false))
     :cljs (reset! (.-running tw) false))
  (timer/stop-timer! (.-timer tw))
  (mapcat (fn [w] (mapcat (fn [b] (map #(.-value ^Task %) @b))
                          (vals @(.-buckets ^TimingWheel w))))
          @(.-wheels tw)))

(extend-protocol ITask
  Task
  (schedule! [this ^TimingWheels tw current]
    (when-not @(.-cancelled? this)
      (let [level (level-for-target @(.-target this) current (.-tick tw)
                                    (.-bucket-count tw))
            current-levels (count #?(:clj (ensure (.-wheels tw))
                                     :cljs @(.-wheels tw)))
            _ (when (> level (dec current-levels))
                (dorun (map #(create-wheel tw %) (range current-levels (inc level)))))
            wheel ^TimingWheel (nth #?(:clj (ensure (.-wheels tw))
                                       :cljs @(.-wheels tw))
                                    level)

            ;; aka bucket trigger-time
            bucket-index (bucket-index-for-target @(.-target this) (.-wheel-tick wheel)
                                                  (.-start-at tw))

            _ (when (nil? (get #?(:clj (ensure (.-buckets wheel))
                                  :cljs @(.-buckets wheel))
                               bucket-index))
                (create-bucket tw wheel level bucket-index current))
            bucket (get #?(:clj (ensure (.-buckets wheel))
                           :cljs @(.-buckets wheel))
                        bucket-index)]
        #?(:clj (alter bucket conj this)
           :cljs (swap! bucket conj this)))))

  (cancel! [this ^TimingWheels tw current]
    (when-not @(.-cancelled? this)
      (reset! (.-cancelled? this) true)
      (let [level (level-for-target @(.-target this) current (.-tick tw) (.-bucket-count tw))]
        (when (>= level 0)
          (when-let [wheel ^TimingWheel (nth @(.-wheels tw) level)]
            #?(:clj (dosync
                     (let [bucket-index (bucket-index-for-target @(.-target this) (.-wheel-tick wheel)
                                                                 (.-start-at tw))]
                       (when-let [bucket (get (ensure (.-buckets wheel)) bucket-index)]
                         (alter bucket disj this))))
               :cljs (let [bucket-index (bucket-index-for-target @(.-target this) (.-wheel-tick wheel)
                                                                 (.-start-at tw))]
                       (when-let [bucket (get @(.-buckets wheel) bucket-index)]
                         (swap! bucket disj this))))))))
    this)

  (execute! [this ^TimingWheels tw current]
    (when-not @(.-cancelled? this)
      #?(:clj (deliver (.-result-promise this) ((.-consumer tw) (.-value this)))
         :cljs ((.-consumer tw) (.-value this)))
      (when (some? (.-interval this))
        (reset! (.-target this) (+ current (.-interval this)))
        #?(:clj (dosync (schedule! this tw current))
           :cljs (schedule! this tw current))))))

(defn pendings [^TimingWheels tw]
  (->> @(.-wheels tw)
       (mapcat #(vals (deref (.-buckets ^TimingWheel %))))
       (reduce #(+ %1 (count (filter (fn [^Task t] (not @(.-cancelled? t))) @%2))) 0)))

;; a wrapper function
(defn cancel-task! [^Task t ^TimingWheels tw current]
  (cancel! t tw current))
