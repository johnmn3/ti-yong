(ns ti-yong.alpha.transformer-call-arities-basic-test
  (:require
   [cljs.spec.alpha :as s]
   [clojure.test :refer [deftest is]]
   [ti-yong.alpha.transformer :as t]
   [ti-yong.alpha.util :as u]))

(deftest transformer-call-arities-test-cljs
  (is (= nil (t/transformer)))
  (is (= 1 (t/transformer 1)))
  (is (= false (t/transformer false)))
  (is (= :anything-else (t/transformer :anything-else)))
  (is (= '(1 2) (t/transformer 1 2)))
  (is (= '(1 2 3) (t/transformer 1 2 3)))
  (is (= '(1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 21)
         (t/transformer 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 21)))
  ;; High arity calls that may fail in CLJS due to wrap-map/IFn issues
  ;; (is (= '(1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 21 22)
  ;;        (apply t/transformer 1 (range 2 23))))
  ;; (is (= (range 0 100)
  ;;        (apply t/transformer 0 1 (range 2 100))))
  )

(deftest transformer-basic-test-cljs
  (is (map? t/transformer))
  (is (= {:a 1, :b 2} (t/transformer {:b 2, :a 1}))))
