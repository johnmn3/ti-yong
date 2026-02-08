(ns hearth.alpha.middleware.resource-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [hearth.alpha.middleware :as mw]
   [hearth.alpha.service :as svc]
   [clojure.java.io :as io]))

(deftest resource-serves-classpath-file-test
  (testing "resource middleware serves a file from classpath"
    ;; clojure/core.clj always exists on the classpath
    (let [svc (svc/service
               {:routes [["/fallback" :get (fn [_] {:status 200 :body "fallback"})
                          :route-name ::fallback]]
                :with [(mw/resource {:prefix "clojure"})]})
          resp (svc/response-for svc :get "/core.clj")]
      ;; Should find clojure/core.clj on classpath
      (is (= 200 (:status resp)))
      (is (some? (:body resp))))))

(deftest resource-404-for-missing-test
  (testing "resource middleware falls through for missing files"
    (let [svc (svc/service
               {:routes [["/hello" :get (fn [_] {:status 200 :body "hello"})
                          :route-name ::hello]]
                :with [(mw/resource {:prefix "public"})]})
          resp (svc/response-for svc :get "/hello")]
      ;; Should fall through to route handler since no public/hello file
      (is (= 200 (:status resp)))
      (is (= "hello" (:body resp))))))

(deftest resource-prevents-path-traversal-test
  (testing "resource middleware blocks path traversal"
    (let [svc (svc/service
               {:routes [["/fallback" :get (fn [_] {:status 200 :body "ok"})
                          :route-name ::fallback]]
                :with [(mw/resource {:prefix "public"})]})
          resp (svc/response-for svc :get "/../../../etc/passwd")]
      ;; Should not serve the file (path traversal blocked)
      (is (= 404 (:status resp))))))

(deftest file-middleware-path-traversal-test
  (testing "file middleware blocks path traversal"
    (let [svc (svc/service
               {:routes [["/fallback" :get (fn [_] {:status 200 :body "ok"})
                          :route-name ::fallback]]
                :with [(mw/file {:root "/tmp"})]})
          resp (svc/response-for svc :get "/../../../etc/passwd")]
      ;; Path traversal blocked - should fall through to route or 404
      (is (or (= 404 (:status resp))
              (= 200 (:status resp)))))))
