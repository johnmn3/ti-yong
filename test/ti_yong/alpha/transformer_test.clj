(ns ti-yong.alpha.transformer-test
  (:require
   [clojure.test :refer [deftest is]]
   [ti-yong.alpha.transformer :as t]))

(deftest minimal-transformer-test
  (is (= 1 1)))
