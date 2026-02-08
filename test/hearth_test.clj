(ns hearth-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [hearth :as http]))

;; Phase 7: Public API tests
;; The hearth namespace is the user-facing entry point.
;; It provides create-server, start, stop, response-for, and
;; re-exports key utilities from sub-namespaces.

(deftest create-server-test
  (testing "create-server creates a server config from a service map"
    (let [service-map {::http/routes [["/hello" :get (fn [_] {:status 200 :body "hi"})
                                       :route-name ::hello]]
                       ::http/port 9090}
          server (http/create-server service-map)]
      (is (map? server))
      (is (= 9090 (:port server)))
      (is (fn? (:handler server))))))

(deftest create-server-defaults-test
  (testing "create-server uses default port 8080"
    (let [service-map {::http/routes [["/hello" :get (fn [_] {:status 200 :body "hi"})
                                       :route-name ::hello]]}
          server (http/create-server service-map)]
      (is (= 8080 (:port server))))))

(deftest response-for-test
  (testing "response-for exercises the service transformer pipeline"
    (let [service-map {::http/routes [["/items" :get (fn [_] {:status 200 :body "list"})
                                       :route-name ::items]
                                      ["/items/:id" :get
                                       (fn [env] {:status 200
                                                  :body (str "item-" (get-in env [:path-params-values "id"]))})
                                       :route-name ::get-item]
                                      ["/items" :post (fn [_] {:status 201 :body "created"})
                                       :route-name ::create]]}]
      (is (= {:status 200 :body "list"}
             (http/response-for service-map :get "/items")))
      (is (= {:status 201 :body "created"}
             (http/response-for service-map :post "/items")))
      (is (= 404 (:status (http/response-for service-map :get "/nope")))))))

(deftest response-for-with-path-params-test
  (testing "response-for handles path parameters"
    (let [service-map {::http/routes [["/users/:id" :get
                                       (fn [env]
                                         {:status 200
                                          :body (get-in env [:path-params-values "id"])})
                                       :route-name ::get-user]]}]
      (is (= "42" (:body (http/response-for service-map :get "/users/42")))))))

(deftest response-for-with-middleware-test
  (testing "response-for works with global middleware"
    (let [log (atom [])
          service-map {::http/routes [["/hello" :get (fn [_] {:status 200 :body "hi"})
                                       :route-name ::hello]]
                       ::http/with [(http/logging-middleware log)]}]
      (http/response-for service-map :get "/hello")
      (is (pos? (count @log))))))

(deftest full-pipeline-test
  (testing "full pipeline with error handling and content-type"
    (let [service-map {::http/routes [["/ok" :get (fn [_] {:status 200 :headers {} :body "fine"})
                                       :route-name ::ok]
                                      ["/boom" :get (fn [_] (throw (ex-info "kaboom" {})))
                                       :route-name ::boom]]
                       ::http/with [(http/error-middleware)
                                    (http/content-type-middleware "application/json")]}]
      ;; Normal response gets content-type
      (let [resp (http/response-for service-map :get "/ok")]
        (is (= 200 (:status resp)))
        (is (= "application/json" (get-in resp [:headers "Content-Type"]))))
      ;; Error is caught and returned as 500
      (let [resp (http/response-for service-map :get "/boom")]
        (is (= 500 (:status resp)))
        (is (re-find #"kaboom" (:body resp)))))))
