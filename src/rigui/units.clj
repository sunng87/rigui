(ns rigui.units)

(defprotocol Convert
  (to-millis [this])
  (to-nanos [this]))

(defrecord Nanosecond [v]
  Convert
  (to-millis [this]
    (* 0.000001 v))
  (to-nanos [this]
    v))
(defn nanos [v] (Nanosecond. v))

(defrecord Millisecond [v]
  Convert
  (to-millis [this]
    v)
  (to-nanos [this]
    (* 1000000 v)))
(defn millis [v] (Millisecond. v))

(defrecord Second [v]
  Convert
  (to-millis [this]
    (* 1000 v))
  (to-nanos [this]
    (* 1000000000 v)))
(defn seconds [v] (Second. v))

(defrecord Minute [v]
  Convert
  (to-millis [this]
    (to-millis (seconds (* 60 v))))
  (to-nanos [this]
    (to-nanos (seconds (* 60 v)))))
(defn minutes [v] (Minute. v))

(defrecord Hour [v]
  Convert
  (to-millis [this]
    (to-millis (minutes (* 60 v))))
  (to-nanos [this]
    (to-nanos (minutes (* 60 v)))))
(defn hours [v] (Hour. v))

(extend-protocol Convert
  java.lang.Long
  (to-millis [this] (to-millis (millis (.longValue this))))
  (to-nanos [this] (to-nanos (millis (.longValue this))))

  java.lang.Integer
  (to-millis [this] (to-millis (millis (.longValue this))))
  (to-nanos [this] (to-nanos (millis (.longValue this)))))
