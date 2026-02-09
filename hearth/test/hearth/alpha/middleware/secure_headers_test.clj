(ns hearth.alpha.middleware.secure-headers-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [hearth.alpha.middleware :as mw]
   [hearth.alpha.service :as svc]))

(deftest secure-headers-adds-defaults-test
  (testing "adds all default security headers to response"
    (let [svc (svc/service {:routes [["/test" :get (fn [_] {:status 200 :body "ok"})
                                      :route-name ::test]]
                             :with [(mw/secure-headers)]})
          resp (svc/response-for svc :get "/test")]
      (is (= 200 (:status resp)))
      (is (= "DENY" (get-in resp [:headers "X-Frame-Options"])))
      (is (= "nosniff" (get-in resp [:headers "X-Content-Type-Options"])))
      (is (= "1; mode=block" (get-in resp [:headers "X-XSS-Protection"])))
      (is (= "max-age=31536000; includeSubdomains"
             (get-in resp [:headers "Strict-Transport-Security"])))
      (is (= "noopen" (get-in resp [:headers "X-Download-Options"])))
      (is (= "none" (get-in resp [:headers "X-Permitted-Cross-Domain-Policies"])))
      (is (some? (get-in resp [:headers "Content-Security-Policy"]))))))

(deftest secure-headers-overrides-test
  (testing "custom overrides replace default values"
    (let [svc (svc/service {:routes [["/test" :get (fn [_] {:status 200 :body "ok"})
                                      :route-name ::test]]
                             :with [(mw/secure-headers {"X-Frame-Options" "SAMEORIGIN"})]})
          resp (svc/response-for svc :get "/test")]
      (is (= "SAMEORIGIN" (get-in resp [:headers "X-Frame-Options"])))
      ;; Other defaults still present
      (is (= "nosniff" (get-in resp [:headers "X-Content-Type-Options"]))))))

(deftest secure-headers-non-map-response-test
  (testing "does not modify non-map responses"
    (let [svc (svc/service {:routes [["/test" :get (fn [_] "plain string")
                                      :route-name ::test]]
                             :with [(mw/secure-headers)]})
          resp (svc/response-for svc :get "/test")]
      (is (= "plain string" resp)))))

(deftest secure-headers-preserves-existing-headers-test
  (testing "merges with existing response headers"
    (let [svc (svc/service {:routes [["/test" :get (fn [_] {:status 200
                                                            :headers {"Content-Type" "text/html"}
                                                            :body "ok"})
                                      :route-name ::test]]
                             :with [(mw/secure-headers)]})
          resp (svc/response-for svc :get "/test")]
      (is (= "text/html" (get-in resp [:headers "Content-Type"])))
      (is (= "DENY" (get-in resp [:headers "X-Frame-Options"]))))))
