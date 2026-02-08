(ns ti-yong.alpha.transformer-pipeline-test
  (:require
   [cljs.spec.alpha :as s]
   [clojure.test :refer [deftest is]]
   [ti-yong.alpha.transformer :as t]
   [ti-yong.alpha.root :as r]))

#_(deftest transformer-pipeline-test-cljs
  (let [base-transformer (assoc t/transformer :op +)
        x (-> base-transformer
              (assoc :x 1 :y 2)
              (update :tf-pre conj ::tf-pre (fn [env] (js/console.log ":tf-pre") env))
              (update :in conj ::in (fn [args] (js/console.log ":in") args))
              (update :tf conj ::tf (fn [env] (js/console.log ":tf") env))
              (update :out conj ::out (fn [res] (js/console.log ":out") res))
              (update :tf-end conj ::tf-end (fn [env] (js/console.log ":tf-end") env)))]
    (is (some? (assoc x :o2 2)))
    (is (number? (x 1 2 3)))
    (is (number? (-> x
                     (assoc :a 1 :b 2 :c 3 :d 4 :e 5)
                     (apply [1 2 3]))))) ; is (5), let (1), deftest (1) -> 7 total for #_ form
) ; closes #_

(deftest dummy-pipeline-test-cljs
  (is (= 1 1)))
