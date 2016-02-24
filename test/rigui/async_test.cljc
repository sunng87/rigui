(ns rigui.async-test
  (:require [rigui.async :as sut]
            #?(:clj [clojure.test :as t :refer :all]
               :cljs [cljs.test :as t :include-macros true])
            #?(:clj [clojure.core.async :as async :refer [go]]
               :cljs [cljs.core.async :as async]))
  #?(:cljs (:require-macros [cljs.core.async.macros :refer [go]])))

(deftest test-delayed-chan
  (testing "base case"
    (let [d-chan (sut/delayed-chan)
          thing (sut/delayed-value :a 500)]
      (go
        (async/>! d-chan thing))
      (async/<!! (go
                   (let [t (async/timeout 100)
                         [_ port] (async/alts! [t d-chan])]
                     (is (= port t)))
                   (let [t2 (async/timeout 140)
                         [v port] (async/alts! [t2 d-chan])]
                     (is (= port d-chan))
                     (is (= v :a)))))
      (async/close! d-chan))))
