(ns ti-yong.alpha.async-invoke-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [ti-yong.alpha.async :as async]
   [ti-yong.alpha.transformer :as t])
  (:import
   [java.util.concurrent CompletableFuture]
   [java.util.function Supplier]))

;; Phase 0.3-0.4: Tests for async-aware pipeline

(deftest sync-path-unchanged
  (testing "sync transformers work exactly as before"
    (let [adder (assoc t/transformer :op +)]
      (is (= 6 (adder 1 2 3)))))

  (testing "sync env-op works as before"
    (let [t* (assoc t/transformer :env-op (fn [env] (apply + (:args env))))]
      (is (= 6 (t* 1 2 3)))))

  (testing "sync pipeline with :tf works as before"
    (let [t* (-> t/transformer
                 (assoc :op +)
                 (update :tf conj
                         ::double-args
                         (fn [env]
                           (update env :args #(mapv (partial * 2) %)))))]
      (is (= 12 (t* 1 2 3))))))  ;; (+ 2 4 6) = 12

(deftest async-op-test
  (testing "async env-op returns deferred result"
    (let [async-adder (assoc t/transformer
                             :env-op (fn [env]
                                       (CompletableFuture/supplyAsync
                                        (reify Supplier
                                          (get [_] (apply + (:args env)))))))]
      (let [result (async-adder 1 2 3)]
        (is (async/deferred? result))
        (is (= 6 (.get ^CompletableFuture result)))))))

(deftest async-tf-step-test
  (testing "async :tf step propagates deferred through pipeline"
    (let [t* (-> t/transformer
                 (assoc :op +)
                 (update :tf conj
                         ::async-tf
                         (fn [env]
                           (CompletableFuture/supplyAsync
                            (reify Supplier
                              (get [_] (update env :args #(mapv inc %))))))))]
      (let [result (t* 1 2 3)]
        (is (async/deferred? result))
        (is (= 9 (.get ^CompletableFuture result)))))))  ;; (+ 2 3 4) = 9

(deftest async-tf-end-step-test
  (testing "async :tf-end step propagates deferred"
    (let [t* (-> t/transformer
                 (assoc :op (fn [] {:status 200 :body "hello"}))
                 (update :tf-end conj
                         ::async-end
                         (fn [env]
                           (CompletableFuture/supplyAsync
                            (reify Supplier
                              (get [_]
                                (update-in env [:res :body] str " world")))))))]
      (let [result (t*)]
        (is (async/deferred? result))
        (is (= {:status 200 :body "hello world"}
               (.get ^CompletableFuture result)))))))

(deftest async-out-step-test
  (testing "async :out step propagates deferred"
    (let [t* (-> t/transformer
                 (assoc :op (fn [] {:data 42}))
                 (update :out conj
                         ::async-out
                         (fn [res]
                           (CompletableFuture/supplyAsync
                            (reify Supplier
                              (get [_] (assoc res :processed true)))))))]
      (let [result (t*)]
        (is (async/deferred? result))
        (is (= {:data 42 :processed true}
               (.get ^CompletableFuture result)))))))

(deftest async-middleware-composition-test
  (testing "async middleware composes via :with"
    (let [delay-mw (-> t/transformer
                       (update :id conj ::delay)
                       (update :tf conj
                               ::delay
                               (fn [env]
                                 (let [cf (CompletableFuture.)]
                                   (future (.complete cf (assoc env :delayed? true)))
                                   cf))))
          composed (-> t/transformer
                       (assoc :op +)
                       (update :with conj delay-mw))]
      (let [result (composed 1 2)]
        (is (async/deferred? result))
        (is (= 3 (.get ^CompletableFuture result)))))))

(deftest mixed-sync-async-pipeline-test
  (testing "sync middleware before and after async step"
    (let [t* (-> t/transformer
                 (assoc :op +)
                 (update :tf conj
                         ::sync-before
                         (fn [env]
                           (assoc env :before true))
                         ::async-middle
                         (fn [env]
                           (CompletableFuture/completedFuture
                            (assoc env :async true)))
                         ::sync-after
                         (fn [env]
                           (assoc env :after true))))]
      (let [result (t* 1 2 3)]
        (is (async/deferred? result))
        (is (= 6 (.get ^CompletableFuture result)))))))
