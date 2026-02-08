(ns hearth.alpha.middleware.not-modified-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [hearth.alpha.middleware :as mw]
   [hearth.alpha.service :as svc]))

(deftest etag-match-returns-304-test
  (testing "matching ETag returns 304"
    (let [svc (svc/service
               {:routes [["/resource" :get
                          (fn [_] {:status 200
                                   :headers {"ETag" "\"abc123\""}
                                   :body "content"})
                          :route-name ::resource]]
                :with [mw/not-modified]})
          resp (svc/response-for svc :get "/resource"
                 {:headers {"if-none-match" "\"abc123\""}})]
      (is (= 304 (:status resp)))
      (is (nil? (:body resp)))
      (is (= "\"abc123\"" (get-in resp [:headers "ETag"]))))))

(deftest etag-mismatch-returns-200-test
  (testing "non-matching ETag returns normal response"
    (let [svc (svc/service
               {:routes [["/resource" :get
                          (fn [_] {:status 200
                                   :headers {"ETag" "\"abc123\""}
                                   :body "content"})
                          :route-name ::resource]]
                :with [mw/not-modified]})
          resp (svc/response-for svc :get "/resource"
                 {:headers {"if-none-match" "\"xyz789\""}})]
      (is (= 200 (:status resp)))
      (is (= "content" (:body resp))))))

(deftest last-modified-match-returns-304-test
  (testing "matching Last-Modified returns 304"
    (let [svc (svc/service
               {:routes [["/resource" :get
                          (fn [_] {:status 200
                                   :headers {"Last-Modified" "Wed, 01 Jan 2025 00:00:00 GMT"}
                                   :body "content"})
                          :route-name ::resource]]
                :with [mw/not-modified]})
          resp (svc/response-for svc :get "/resource"
                 {:headers {"if-modified-since" "Wed, 01 Jan 2025 00:00:00 GMT"}})]
      (is (= 304 (:status resp)))
      (is (nil? (:body resp))))))

(deftest no-conditional-headers-returns-200-test
  (testing "request without conditional headers returns normal response"
    (let [svc (svc/service
               {:routes [["/resource" :get
                          (fn [_] {:status 200
                                   :headers {"ETag" "\"abc123\""}
                                   :body "content"})
                          :route-name ::resource]]
                :with [mw/not-modified]})
          resp (svc/response-for svc :get "/resource")]
      (is (= 200 (:status resp)))
      (is (= "content" (:body resp))))))

(deftest non-200-not-checked-test
  (testing "non-200 responses are not checked for 304"
    (let [svc (svc/service
               {:routes [["/resource" :get
                          (fn [_] {:status 201
                                   :headers {"ETag" "\"abc123\""}
                                   :body "created"})
                          :route-name ::resource]]
                :with [mw/not-modified]})
          resp (svc/response-for svc :get "/resource"
                 {:headers {"if-none-match" "\"abc123\""}})]
      (is (= 201 (:status resp))))))
