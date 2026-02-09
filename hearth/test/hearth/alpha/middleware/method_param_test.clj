(ns hearth.alpha.middleware.method-param-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [hearth.alpha.middleware :as mw]
   [hearth.alpha.service :as svc]))

(deftest method-param-overrides-post-test
  (testing "POST with _method=PUT routes to PUT handler"
    (let [svc (svc/service {:routes [["/resource" :put (fn [_] {:status 200 :body "updated"})
                                      :route-name ::update-resource]]
                             :with [mw/query-params (mw/method-param)]})
          resp (svc/response-for svc :post "/resource"
                 {:query-string "_method=PUT"})]
      (is (= 200 (:status resp)))
      (is (= "updated" (:body resp))))))

(deftest method-param-overrides-post-to-delete-test
  (testing "POST with _method=DELETE routes to DELETE handler"
    (let [svc (svc/service {:routes [["/resource" :delete (fn [_] {:status 200 :body "deleted"})
                                      :route-name ::delete-resource]]
                             :with [mw/query-params (mw/method-param)]})
          resp (svc/response-for svc :post "/resource"
                 {:query-string "_method=DELETE"})]
      (is (= 200 (:status resp)))
      (is (= "deleted" (:body resp))))))

(deftest method-param-ignores-get-test
  (testing "GET requests are not overridden even with _method param"
    (let [svc (svc/service {:routes [["/resource" :get (fn [_] {:status 200 :body "ok"})
                                      :route-name ::get-resource]]
                             :with [mw/query-params (mw/method-param)]})
          resp (svc/response-for svc :get "/resource"
                 {:query-string "_method=DELETE"})]
      (is (= 200 (:status resp)))
      (is (= "ok" (:body resp))))))

(deftest method-param-no-override-without-param-test
  (testing "POST without _method param routes normally"
    (let [svc (svc/service {:routes [["/resource" :post (fn [_] {:status 201 :body "created"})
                                      :route-name ::create-resource]]
                             :with [mw/query-params (mw/method-param)]})
          resp (svc/response-for svc :post "/resource")]
      (is (= 201 (:status resp)))
      (is (= "created" (:body resp))))))

(deftest method-param-custom-name-test
  (testing "custom param name works"
    (let [svc (svc/service {:routes [["/resource" :put (fn [_] {:status 200 :body "updated"})
                                      :route-name ::update-resource]]
                             :with [mw/query-params (mw/method-param "http-method")]})
          resp (svc/response-for svc :post "/resource"
                 {:query-string "http-method=PUT"})]
      (is (= 200 (:status resp)))
      (is (= "updated" (:body resp))))))

(deftest method-param-case-insensitive-test
  (testing "method name is lowercased"
    (let [svc (svc/service {:routes [["/resource" :put (fn [_] {:status 200 :body "updated"})
                                      :route-name ::update-resource]]
                             :with [mw/query-params (mw/method-param)]})
          resp (svc/response-for svc :post "/resource"
                 {:query-string "_method=put"})]
      (is (= 200 (:status resp))))))
