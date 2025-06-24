(ns ti-yong.alpha.transformer-test
  (:require
   [cljs.spec.alpha :as s]
   [clojure.test :refer [deftest is]]
   [ti-yong.alpha.transformer :as t]))

(deftest minimal-transformer-test-cljs
  (is (= 1 1)))
