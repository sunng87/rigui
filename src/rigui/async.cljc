(ns rigui.async
  (:refer-clojure :exclude [delay])
  (:require [rigui.core :as rigui]
            [clojure.core.async :refer [go chan go-loop <!! >!!]]
            [clojure.core.async.impl.protocols :refer :all]))

(defprotocol Delayed
  (delay [this])
  (value [this]))

(deftype DelayedValue [value delay]
  Delayed
  (delay [this] delay)
  (value [this] value))

(deftype DelayedChannel [tx rx timer]
  Channel
  (close! [this]
    (close! tx)
    (close! rx))
  (closed? [this]
    (and (closed? tx) (closed? rx)))

  WritePort
  (put! [this value fn-handler]
    (if (satisfies? Delayed value)
      (put! tx value fn-handler)
      (throw (ex-info "value should implement Delayed protocol"
                      {:reason ::invalid-value}))))

  ReadPort
  (take! [this fn-handler]
    (take! rx fn-handler)))

(defn delayed-chan
  ([] (delayed-chan nil 1 8))
  ([buf] (delayed-chan buf 1 8))
  ([buf tick buckets]
   (let [tx (chan)
         rx (chan buf)
         timer-handler (fn [v] (go (>!! rx v)))
         timer (rigui/start tick buckets timer-handler)]
     (go-loop []
       (when-let [v (<!! tx)]
         (rigui/schedule! timer (value v) (delay v))
         (recur)))
     (DelayedChannel. tx rx timer))))
