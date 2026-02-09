(ns hearth.alpha.default-middleware-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [hearth.alpha :as h]
   [hearth.alpha.service :as svc]
   [hearth.alpha.middleware :as mw]))

(deftest default-middleware-returns-vector-test
  (testing "default-middleware returns a vector of middleware"
    (let [defaults (h/default-middleware)]
      (is (vector? defaults))
      (is (pos? (count defaults))))))

(deftest default-middleware-includes-secure-headers-test
  (testing "responses include security headers by default"
    (let [resp (h/response-for
                {::h/routes [["/test" :get
                              (fn [_] {:status 200 :headers {} :body "ok"})
                              :route-name ::test]]}
                :get "/test")]
      (is (= 200 (:status resp)))
      (is (= "DENY" (get-in resp [:headers "X-Frame-Options"])))
      (is (= "nosniff" (get-in resp [:headers "X-Content-Type-Options"]))))))

(deftest default-middleware-not-found-test
  (testing "unmatched routes get 404 from default not-found-handler"
    (let [resp (h/response-for
                {::h/routes [["/exists" :get
                              (fn [_] {:status 200 :headers {} :body "ok"})
                              :route-name ::exists]]}
                :get "/nope")]
      (is (= 404 (:status resp))))))

(deftest default-middleware-head-method-test
  (testing "HEAD requests return headers but no body"
    (let [resp (h/response-for
                {::h/routes [["/test" :get
                              (fn [_] {:status 200
                                       :headers {"Content-Length" "5"}
                                       :body "hello"})
                              :route-name ::test]]}
                :head "/test")]
      (is (= 200 (:status resp)))
      (is (nil? (:body resp)))
      (is (= "5" (get-in resp [:headers "Content-Length"]))))))

(deftest default-middleware-query-params-test
  (testing "query params are automatically parsed"
    (let [resp (h/response-for
                {::h/routes [["/search" :get
                              (fn [env]
                                {:status 200 :headers {}
                                 :body (get (:query-params env) "q")})
                              :route-name ::search]]}
                :get "/search"
                {:query-string "q=clojure"})]
      (is (= 200 (:status resp)))
      (is (= "clojure" (:body resp))))))

(deftest default-middleware-method-param-test
  (testing "method override via _method param works"
    (let [resp (h/response-for
                {::h/routes [["/resource" :put
                              (fn [_] {:status 200 :headers {} :body "updated"})
                              :route-name ::update]]}
                :post "/resource"
                {:query-string "_method=PUT"})]
      (is (= 200 (:status resp)))
      (is (= "updated" (:body resp))))))

(deftest default-middleware-not-modified-test
  (testing "304 returned when ETag matches"
    (let [resp (h/response-for
                {::h/routes [["/test" :get
                              (fn [_] {:status 200
                                       :headers {"ETag" "\"abc123\""}
                                       :body "content"})
                              :route-name ::test]]}
                :get "/test"
                {:headers {"if-none-match" "\"abc123\""}})]
      (is (= 304 (:status resp)))
      (is (nil? (:body resp))))))

(deftest default-middleware-error-handler-test
  (testing "exceptions are caught and return 500"
    (let [resp (h/response-for
                {::h/routes [["/boom" :get
                              (fn [_] (throw (Exception. "kaboom")))
                              :route-name ::boom]]}
                :get "/boom")]
      (is (= 500 (:status resp))))))

(deftest raw-mode-skips-defaults-test
  (testing "::raw? true skips default middleware"
    (let [resp (h/response-for
                {::h/routes [["/test" :get
                              (fn [_] {:status 200 :headers {} :body "ok"})
                              :route-name ::test]]
                 ::h/raw? true}
                :get "/test")]
      (is (= 200 (:status resp)))
      ;; No secure headers when raw
      (is (nil? (get-in resp [:headers "X-Frame-Options"]))))))

(deftest user-middleware-prepended-to-defaults-test
  (testing "user :with middleware runs before defaults"
    (let [log (atom [])
          log-mw (mw/logging log)
          resp (h/response-for
                {::h/routes [["/test" :get
                              (fn [_] {:status 200 :headers {} :body "ok"})
                              :route-name ::test]]
                 ::h/with [log-mw]}
                :get "/test")]
      (is (= 200 (:status resp)))
      ;; Logging middleware ran
      (is (= 1 (count @log)))
      ;; And secure headers were still applied (from defaults)
      (is (= "DENY" (get-in resp [:headers "X-Frame-Options"]))))))
