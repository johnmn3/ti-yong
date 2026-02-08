(ns hearth.error-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [hearth.error :as err]
   [ti-yong.alpha.transformer :as t]))

;; Phase 4: Error handler mixin
;; Wraps :op/:env-op execution in try/catch via a :tf step,
;; converting exceptions into error response maps.

(deftest error-handler-test
  (testing "error handler catches exceptions from :op and returns error response"
    (let [handler (-> t/transformer
                      (update :with conj err/error-handler)
                      (assoc :op (fn [] (throw (ex-info "boom" {:code 42})))))]
      (let [result (handler)]
        (is (= 500 (:status result)))
        (is (string? (:body result)))
        (is (re-find #"boom" (:body result))))))

  (testing "error handler passes through successful results"
    (let [handler (-> t/transformer
                      (update :with conj err/error-handler)
                      (assoc :op (fn [] {:status 200 :body "ok"})))]
      (is (= {:status 200 :body "ok"} (handler)))))

  (testing "error handler catches exceptions from :env-op"
    (let [handler (-> t/transformer
                      (update :with conj err/error-handler)
                      (assoc :env-op (fn [env]
                                       (throw (Exception. "env-op failure")))))]
      (let [result (handler)]
        (is (= 500 (:status result)))
        (is (re-find #"env-op failure" (:body result)))))))

(deftest custom-error-handler-test
  (testing "custom error handler allows custom error-to-response fn"
    (let [handler (-> t/transformer
                      (update :with conj
                              (err/error-handler-with
                               (fn [e env]
                                 {:status 503
                                  :headers {"Retry-After" "30"}
                                  :body (str "Service unavailable: " (.getMessage e))})))
                      (assoc :op (fn [] (throw (ex-info "overloaded" {})))))]
      (let [result (handler)]
        (is (= 503 (:status result)))
        (is (= "30" (get-in result [:headers "Retry-After"])))
        (is (re-find #"overloaded" (:body result))))))

  (testing "custom error handler receives the env"
    (let [observed (atom nil)
          handler (-> t/transformer
                      (update :with conj
                              (err/error-handler-with
                               (fn [e env]
                                 (reset! observed (:custom-key env))
                                 {:status 500 :body "error"})))
                      (assoc :custom-key "my-value"
                             :op (fn [] (throw (Exception. "test")))))]
      (handler)
      (is (= "my-value" @observed)))))

(deftest error-handler-preserves-non-exception-results-test
  (testing "nil results pass through without error"
    (let [handler (-> t/transformer
                      (update :with conj err/error-handler)
                      (assoc :op (fn [] nil)))]
      (is (nil? (handler)))))

  (testing "string results pass through"
    (let [handler (-> t/transformer
                      (update :with conj err/error-handler)
                      (assoc :op (fn [] "hello")))]
      (is (= "hello" (handler))))))
