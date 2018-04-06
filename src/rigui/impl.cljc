(ns rigui.impl
  (:require [rigui.math :as math]
            [rigui.utils :refer [now]]
            [rigui.timer.protocol :as timer]
            [rigui.timer.platform :as platform]
            [rigui.timer.virtual :as virtual])
  #?(:clj (:import [clojure.lang IDeref IPending IBlockingDeref]
                   [java.io Writer])))

(defrecord TimingWheels [buckets tick bucket-count start-at timer consumer running])

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

(defn create-bucket [^TimingWheels parent level trigger-time current-time]
  #?@(:clj [(alter (.-buckets parent) assoc trigger-time (ref #{}))
            (send (.-running parent) (fn [running]
                                       (when running
                                         (timer/schedule! (.-timer parent) [parent trigger-time]
                                                          (- trigger-time current-time)))
                                       running))]
      :cljs [(swap! (.-buckets parent) assoc trigger-time (atom #{}))
             (when @(.-running parent)
               (timer/schedule! (.-timer parent) [parent trigger-time]
                                (- trigger-time current-time)))]))

(defn book-keeping [[^TimingWheels parent trigger-time]]
  (let [bucket #?(:clj (dosync
                        (let [b (get (ensure (.-buckets parent)) trigger-time)]
                          (alter (.-buckets parent) dissoc trigger-time)
                          (ensure b)))
                  :cljs (let [b (get @(.-buckets parent) trigger-time)]
                          (swap! (.-buckets parent) dissoc trigger-time)
                          @b))]
    (doseq [^Task t bucket]
      (let [current (now)]
        (if (<= (- @(.-target t) current) (.-tick parent))
          (execute! t parent current)
          #?(:clj (dosync (schedule! t parent current))
             :cljs (schedule! t parent current)))))))

(defn start [tick bucket-count consumer start-at]
  (let [timer-impl (if virtual/*using-virtual-timer*
                     (virtual/start-timer book-keeping)
                     (platform/start-timer book-keeping))]
    (TimingWheels. #?(:clj (ref {}) :cljs (atom {}))
                 tick bucket-count start-at
                 timer-impl consumer
                 #?(:clj (agent true) :cljs (atom true)))))

(defn wheel-tick [^TimingWheels tw level]
  ;; TODO: cache this
  (* (.-tick tw) (long (math/pow (.-bucket-count tw) level))))

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
  (mapcat (fn [b] (map #(.-value ^Task %) @b))
          (vals @(.-buckets ^TimingWheels tw))))

(extend-protocol ITask
  Task
  (schedule! [this ^TimingWheels tw current]
    (when-not @(.-cancelled? this)
      (let [level (level-for-target @(.-target this) current (.-tick tw)
                                    (.-bucket-count tw))

            level-tick (wheel-tick tw level)

            ;; aka bucket trigger-time
            bucket-index (bucket-index-for-target @(.-target this) level-tick (.-start-at tw))

            _ (when (nil? (get #?(:clj (ensure (.-buckets tw))
                                  :cljs @(.-buckets tw))
                               bucket-index))
                (create-bucket tw level bucket-index current))
            bucket (get #?(:clj (ensure (.-buckets tw))
                           :cljs @(.-buckets tw))
                        bucket-index)]
        #?(:clj (alter bucket conj this)
           :cljs (swap! bucket conj this)))))

  (cancel! [this ^TimingWheels tw current]
    (when-not @(.-cancelled? this)
      (reset! (.-cancelled? this) true)
      (let [level (level-for-target @(.-target this) current (.-tick tw) (.-bucket-count tw))
            level-tick (wheel-tick tw level)]
        (when (>= level 0)
          #?(:clj (dosync
                   (let [bucket-index (bucket-index-for-target @(.-target this) level-tick
                                                               (.-start-at tw))]
                     (when-let [bucket (get (ensure (.-buckets tw)) bucket-index)]
                       (alter bucket disj this))))
             :cljs (let [bucket-index (bucket-index-for-target @(.-target this) level-tick
                                                               (.-start-at tw))]
                     (when-let [bucket (get @(.-buckets tw) bucket-index)]
                       (swap! bucket disj this)))))))
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
  (->> @(.-buckets tw)
       (reduce #(+ %1 (count (filter (fn [^Task t] (not @(.-cancelled? t))) @%2))) 0)))

;; a wrapper function
(defn cancel-task! [^Task t ^TimingWheels tw current]
  (cancel! t tw current))
