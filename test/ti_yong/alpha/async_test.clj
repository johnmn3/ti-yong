(ns ti-yong.alpha.async-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [ti-yong.alpha.async :as async])
  (:import
   [java.util.concurrent CompletableFuture]))

(deftest deferred?-test
  (testing "non-deferred values return false"
    (is (false? (async/deferred? 42)))
    (is (false? (async/deferred? nil)))
    (is (false? (async/deferred? {:a 1})))
    (is (false? (async/deferred? "hello")))
    (is (false? (async/deferred? [1 2 3]))))

  (testing "CompletableFuture returns true"
    (is (true? (async/deferred? (CompletableFuture/completedFuture 42))))
    (is (true? (async/deferred? (CompletableFuture.))))))

(deftest then-test
  (testing "chains a function onto a completed future"
    (let [cf (async/then (CompletableFuture/completedFuture 10) inc)]
      (is (instance? CompletableFuture cf))
      (is (= 11 (.get cf)))))

  (testing "chains a function onto a pending future"
    (let [cf (CompletableFuture.)
          chained (async/then cf #(* % 2))]
      (.complete cf 21)
      (is (= 42 (.get chained)))))

  (testing "chains multiple functions"
    (let [result (-> (CompletableFuture/completedFuture 5)
                     (async/then inc)
                     (async/then #(* % 10)))]
      (is (= 60 (.get result))))))

(deftest resolved-test
  (testing "creates a completed future with the given value"
    (is (= 42 (.get (async/resolved 42))))
    (is (nil? (.get (async/resolved nil))))))

(deftest ->deferred-test
  (testing "wraps non-deferred values in a completed future"
    (let [d (async/->deferred 42)]
      (is (async/deferred? d))
      (is (= 42 (.get d)))))

  (testing "passes through existing deferred values"
    (let [cf (CompletableFuture/completedFuture 42)
          d (async/->deferred cf)]
      (is (identical? cf d)))))
