(ns rigui.timer.protocol)

(defprotocol TimerProtocol
  (schedule! [this value delay])
  (stop-timer! [this]))
