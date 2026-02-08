(ns hearth.alpha.middleware.path-params-decoder-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [hearth.alpha.middleware :as mw]
   [hearth.alpha.service :as svc]))

;; The router now URL-decodes path params by default.
;; The path-params-decoder middleware provides an additional layer for
;; explicit use at route-level or for double-encoded values.

(deftest router-decodes-path-params-test
  (testing "router URL-decodes path parameter values by default"
    (let [svc (svc/service {:routes [["/items/:id" :get
                                      (fn [req]
                                        {:status 200
                                         :body (get-in req [:path-params-values "id"])})
                                      :route-name ::get-item]]})
          resp (svc/response-for svc :get "/items/hello%20world")]
      (is (= 200 (:status resp)))
      (is (= "hello world" (:body resp))))))

(deftest router-decodes-special-characters-test
  (testing "router decodes special URL characters in path params"
    (let [svc (svc/service {:routes [["/items/:id" :get
                                      (fn [req]
                                        {:status 200
                                         :body (get-in req [:path-params-values "id"])})
                                      :route-name ::get-item]]})
          resp (svc/response-for svc :get "/items/foo%26bar%3Dbaz")]
      (is (= "foo&bar=baz" (:body resp))))))

(deftest path-params-decoder-middleware-test
  (testing "middleware also decodes (additional pass for route-level use)"
    (let [svc (svc/service {:routes [["/items/:id" :get
                                      (fn [req]
                                        {:status 200
                                         :body (get-in req [:path-params-values "id"])})
                                      :route-name ::get-item]]
                             :with [mw/path-params-decoder]})
          resp (svc/response-for svc :get "/items/hello%20world")]
      (is (= 200 (:status resp)))
      (is (= "hello world" (:body resp))))))

(deftest preserves-unencoded-params-test
  (testing "passes through already-decoded params unchanged"
    (let [svc (svc/service {:routes [["/items/:id" :get
                                      (fn [req]
                                        {:status 200
                                         :body (get-in req [:path-params-values "id"])})
                                      :route-name ::get-item]]})
          resp (svc/response-for svc :get "/items/simple")]
      (is (= "simple" (:body resp))))))

(deftest no-path-params-test
  (testing "handles requests with no path params gracefully"
    (let [svc (svc/service {:routes [["/items" :get
                                      (fn [_] {:status 200 :body "list"})
                                      :route-name ::list-items]]})
          resp (svc/response-for svc :get "/items")]
      (is (= 200 (:status resp)))
      (is (= "list" (:body resp))))))
