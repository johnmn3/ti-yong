(ns hearth.alpha.middleware.head-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [hearth.alpha.middleware :as mw]
   [hearth.alpha.service :as svc]))

(deftest head-converts-to-get-test
  (testing "HEAD request routes to GET handler"
    (let [svc (svc/service
               {:routes [["/hello" :get (fn [_] {:status 200 :body "hi"
                                                  :headers {"Content-Length" "2"}})
                          :route-name ::hello]]
                :with [mw/head-method]})
          resp (svc/response-for svc :head "/hello")]
      (is (= 200 (:status resp)))
      ;; Body should be stripped for HEAD
      (is (nil? (:body resp))))))

(deftest head-preserves-headers-test
  (testing "HEAD response preserves headers from GET handler"
    (let [svc (svc/service
               {:routes [["/data" :get (fn [_] {:status 200
                                                 :headers {"Content-Type" "text/html"
                                                           "Content-Length" "100"}
                                                 :body "<html>large page</html>"})
                          :route-name ::data]]
                :with [mw/head-method]})
          resp (svc/response-for svc :head "/data")]
      (is (= 200 (:status resp)))
      (is (= "text/html" (get-in resp [:headers "Content-Type"])))
      (is (nil? (:body resp))))))

(deftest head-does-not-affect-get-test
  (testing "GET requests pass through unchanged"
    (let [svc (svc/service
               {:routes [["/hello" :get (fn [_] {:status 200 :body "hi"})
                          :route-name ::hello]]
                :with [mw/head-method]})
          resp (svc/response-for svc :get "/hello")]
      (is (= 200 (:status resp)))
      (is (= "hi" (:body resp))))))

(deftest head-does-not-affect-post-test
  (testing "POST requests pass through unchanged"
    (let [svc (svc/service
               {:routes [["/submit" :post (fn [_] {:status 201 :body "created"})
                          :route-name ::submit]]
                :with [mw/head-method]})
          resp (svc/response-for svc :post "/submit")]
      (is (= 201 (:status resp)))
      (is (= "created" (:body resp))))))
