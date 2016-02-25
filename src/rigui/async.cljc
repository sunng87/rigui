(ns rigui.async
  (:refer-clojure :exclude [delay])
  (:require [rigui.core :as rigui]
            #?@(:clj [[clojure.core.async :refer [go chan go-loop <! >!]]
                      [clojure.core.async.impl.protocols :as p]]
                :cljs [[cljs.core.async :refer [chan <! >!]]
                       [cljs.core.async.impl.protocols :as p]]))
  #?(:cljs (:require-macros [cljs.core.async.macros :refer [go go-loop]])))

(defprotocol Delayed
  (delay [this])
  (value [this]))

(deftype DelayedValue [value delay]
  Delayed
  (delay [this] delay)
  (value [this] value))

(defn delayed-value [value delay]
  (DelayedValue. value delay))

(deftype DelayedChannel [tx rx timer]
  p/Channel
  (close! [this]
    (p/close! tx)
    (p/close! rx)
    (rigui/stop timer))
  (closed? [this]
    (and (p/closed? tx) (p/closed? rx)))

  p/WritePort
  (put! [this value fn-handler]
    (if (satisfies? Delayed value)
      (p/put! tx value fn-handler)
      (throw (ex-info "value should implement Delayed protocol"
                      {:reason ::invalid-value}))))

  p/ReadPort
  (take! [this fn-handler]
    (p/take! rx fn-handler)))

(defn delayed-chan
  ([] (delayed-chan nil 1 8))
  ([buf] (delayed-chan buf 1 8))
  ([buf tick buckets]
   (let [tx (chan)
         rx (chan buf)
         timer-handler (fn [v] (go (>! rx v)))
         timer (rigui/start tick buckets timer-handler)]
     (go-loop []
       (when-let [v (<! tx)]
         (rigui/later! timer (value v) (delay v))
         (recur)))
     (DelayedChannel. tx rx timer))))
