(ns hearth.alpha.request-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [hearth.alpha.request :as req]
   [ti-yong.alpha.transformer :as t]))

;; Phase 1: Request transformer primitives
;; A request-tf wraps a Ring request map as a transformer,
;; making it callable, composable, and inspectable.

(deftest request-transformer-test
  (testing "request-tf wraps a Ring request map as a transformer"
    (let [ring-req {:server-port 8080
                    :server-name "localhost"
                    :remote-addr "127.0.0.1"
                    :uri "/"
                    :scheme :http
                    :request-method :get
                    :headers {}}
          r (req/request ring-req)]
      ;; Ring keys are preserved
      (is (= 8080 (:server-port r)))
      (is (= "localhost" (:server-name r)))
      (is (= "/" (:uri r)))
      (is (= :get (:request-method r)))
      (is (= :http (:scheme r)))
      ;; It's a transformer
      (is (vector? (:id r)))
      (is (some #(= ::req/request %) (:id r))))))

(deftest request-accessor-helpers-test
  (testing "path returns the URI"
    (let [r (req/request {:uri "/foo/bar" :request-method :get :headers {}})]
      (is (= "/foo/bar" (req/path r)))))

  (testing "method returns the request method"
    (let [r (req/request {:uri "/" :request-method :post :headers {}})]
      (is (= :post (req/method r)))))

  (testing "header returns a specific header (case-insensitive lookup)"
    (let [r (req/request {:uri "/" :request-method :get
                          :headers {"content-type" "application/json"
                                    "accept" "text/html"}})]
      (is (= "application/json" (req/header r "content-type")))
      (is (= "text/html" (req/header r "accept"))))))

(deftest request-query-string-test
  (testing "query-string is available on the request"
    (let [r (req/request {:uri "/search"
                          :request-method :get
                          :query-string "q=clojure&page=1"
                          :headers {}})]
      (is (= "q=clojure&page=1" (:query-string r))))))

(deftest request-body-test
  (testing "body is available on the request"
    (let [r (req/request {:uri "/items"
                          :request-method :post
                          :body "{\"name\":\"test\"}"
                          :headers {"content-type" "application/json"}})]
      (is (= "{\"name\":\"test\"}" (:body r))))))

(deftest mock-request-helpers-test
  (testing "mock-request creates a minimal Ring request transformer"
    (let [r (req/mock-request :get "/api/items")]
      (is (= :get (:request-method r)))
      (is (= "/api/items" (:uri r)))
      (is (= "localhost" (:server-name r)))
      (is (= :http (:scheme r)))
      (is (vector? (:id r)))))

  (testing "mock-request with POST and body"
    (let [r (req/mock-request :post "/api/items" {:body "hello"})]
      (is (= :post (:request-method r)))
      (is (= "/api/items" (:uri r)))
      (is (= "hello" (:body r))))))

(deftest request-is-callable-test
  (testing "request transformer is callable"
    ;; When invoked with no args and no :op, should return nil or the default identity
    (let [r (req/request {:uri "/" :request-method :get :headers {}})]
      ;; Default op is identity-like, calling with no args returns nil
      (is (nil? (r))))))

(deftest to-ring-request-test
  (testing "to-ring extracts a plain Ring request map"
    (let [r (req/request {:uri "/test" :request-method :get :headers {"accept" "text/html"}})
          ring-req (req/to-ring r)]
      (is (= "/test" (:uri ring-req)))
      (is (= :get (:request-method ring-req)))
      (is (map? (:headers ring-req)))
      ;; Should be a plain map, not a transformer
      (is (not (vector? (:id ring-req)))))))
