(ns rigui.runner
  (:require [cljs.test :as test]
            [doo.runner :refer-macros [doo-tests]]
            [rigui.core-test]
            [rigui.async-test]))

(enable-console-print!)
(doo-tests 'rigui.core-test 'rigui.async-test)
