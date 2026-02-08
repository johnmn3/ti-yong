(ns hearth.response-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [hearth.response :as resp]
   [ti-yong.alpha.transformer :as t]))

;; Phase 1: Response transformer primitives
;; A response-tf wraps a Ring response map (status, headers, body)
;; as a transformer. Response helpers create pre-configured response
;; transformers for common HTTP status codes.

(deftest response-transformer-test
  (testing "response-tf is a transformer with Ring response keys"
    (let [r (resp/response 200 "OK")]
      (is (= 200 (:status r)))
      (is (= "OK" (:body r)))
      (is (map? (:headers r)))
      ;; It should be a transformer (has :id, :tf-pre, etc.)
      (is (vector? (:id r)))
      (is (some #(= ::resp/response %) (:id r))))))

(deftest ok-test
  (testing "ok returns 200 response"
    (let [r (resp/ok "hello")]
      (is (= 200 (:status r)))
      (is (= "hello" (:body r)))
      (is (map? (:headers r)))))

  (testing "ok with nil body"
    (let [r (resp/ok nil)]
      (is (= 200 (:status r)))
      (is (nil? (:body r))))))

(deftest created-test
  (testing "created returns 201 response"
    (let [r (resp/created "/items/1" {:id 1})]
      (is (= 201 (:status r)))
      (is (= {:id 1} (:body r)))
      (is (= "/items/1" (get-in r [:headers "Location"]))))))

(deftest accepted-test
  (testing "accepted returns 202 response"
    (let [r (resp/accepted "processing")]
      (is (= 202 (:status r)))
      (is (= "processing" (:body r))))))

(deftest no-content-test
  (testing "no-content returns 204 response with nil body"
    (let [r (resp/no-content)]
      (is (= 204 (:status r)))
      (is (nil? (:body r))))))

(deftest redirect-test
  (testing "redirect returns 302 with Location header"
    (let [r (resp/redirect "/new-location")]
      (is (= 302 (:status r)))
      (is (= "/new-location" (get-in r [:headers "Location"]))))))

(deftest bad-request-test
  (testing "bad-request returns 400"
    (let [r (resp/bad-request "invalid input")]
      (is (= 400 (:status r)))
      (is (= "invalid input" (:body r))))))

(deftest unauthorized-test
  (testing "unauthorized returns 401"
    (let [r (resp/unauthorized "not authenticated")]
      (is (= 401 (:status r)))
      (is (= "not authenticated" (:body r))))))

(deftest forbidden-test
  (testing "forbidden returns 403"
    (let [r (resp/forbidden "access denied")]
      (is (= 403 (:status r)))
      (is (= "access denied" (:body r))))))

(deftest not-found-test
  (testing "not-found returns 404"
    (let [r (resp/not-found "no such resource")]
      (is (= 404 (:status r)))
      (is (= "no such resource" (:body r))))))

(deftest internal-error-test
  (testing "internal-error returns 500"
    (let [r (resp/internal-error "something broke")]
      (is (= 500 (:status r)))
      (is (= "something broke" (:body r))))))

(deftest response-header-manipulation-test
  (testing "headers can be added via assoc-in"
    (let [r (-> (resp/ok "hello")
                (assoc-in [:headers "Content-Type"] "text/plain"))]
      (is (= "text/plain" (get-in r [:headers "Content-Type"])))))

  (testing "content-type helper sets Content-Type header"
    (let [r (resp/content-type (resp/ok "hello") "application/json")]
      (is (= "application/json" (get-in r [:headers "Content-Type"]))))))

(deftest response-is-callable-test
  (testing "response transformer is callable (returns :res from pipeline)"
    ;; When invoked, the response transformer runs its pipeline.
    ;; The default :op for a response should return the body.
    (let [r (resp/ok "hello")]
      ;; Calling the response should produce the body as the result
      (is (= "hello" (r))))))

(deftest response-with-custom-headers-test
  (testing "response can be constructed with custom headers"
    (let [r (resp/response 200 "ok" {"X-Custom" "value" "Content-Type" "text/html"})]
      (is (= 200 (:status r)))
      (is (= "ok" (:body r)))
      (is (= "value" (get-in r [:headers "X-Custom"])))
      (is (= "text/html" (get-in r [:headers "Content-Type"]))))))

(deftest to-ring-response-test
  (testing "to-ring extracts a plain Ring response map"
    (let [r (resp/ok "hello")
          ring-resp (resp/to-ring r)]
      (is (= 200 (:status ring-resp)))
      (is (= "hello" (:body ring-resp)))
      (is (map? (:headers ring-resp)))
      ;; Should be a plain map, not a transformer
      (is (not (vector? (:id ring-resp)))))))
