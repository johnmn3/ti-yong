(ns hearth.adapter.ring-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [hearth.adapter.ring :as ring]
   [hearth.service :as svc]))

;; Phase 6: Ring/Jetty adapter
;; Converts a service transformer into a Ring handler function,
;; and provides Jetty server lifecycle management.

(deftest service->ring-handler-test
  (testing "service->handler returns a Ring-compatible handler fn"
    (let [svc (svc/service
               {:routes [["/hello" :get (fn [_] {:status 200 :headers {} :body "hi"})
                          :route-name ::hello]]})
          handler (ring/service->handler svc)]
      ;; handler should be a function
      (is (fn? handler))
      ;; handler takes a Ring request map and returns a Ring response map
      (let [resp (handler {:request-method :get
                           :uri "/hello"
                           :headers {}
                           :scheme :http
                           :server-name "localhost"
                           :server-port 80})]
        (is (= 200 (:status resp)))
        (is (= "hi" (:body resp))))))

  (testing "service->handler returns 404 for unmatched routes"
    (let [svc (svc/service
               {:routes [["/hello" :get (fn [_] {:status 200 :headers {} :body "hi"})
                          :route-name ::hello]]})
          handler (ring/service->handler svc)]
      (let [resp (handler {:request-method :get
                           :uri "/nope"
                           :headers {}
                           :scheme :http
                           :server-name "localhost"
                           :server-port 80})]
        (is (= 404 (:status resp)))))))

(deftest ring-response-normalization-test
  (testing "handler normalizes response to ensure :headers is a map"
    (let [svc (svc/service
               {:routes [["/test" :get (fn [_] {:status 200 :body "ok"})
                          :route-name ::test]]})
          handler (ring/service->handler svc)]
      (let [resp (handler {:request-method :get
                           :uri "/test"
                           :headers {}
                           :scheme :http
                           :server-name "localhost"
                           :server-port 80})]
        (is (map? (:headers resp)))))))

(deftest server-config-test
  (testing "create-server produces a server config map"
    (let [svc (svc/service
               {:routes [["/hello" :get (fn [_] {:status 200 :body "hi"})
                          :route-name ::hello]]})
          server-cfg (ring/create-server svc {:port 8888 :join? false})]
      (is (map? server-cfg))
      (is (= 8888 (:port server-cfg)))
      (is (fn? (:handler server-cfg)))
      (is (false? (:join? server-cfg))))))
