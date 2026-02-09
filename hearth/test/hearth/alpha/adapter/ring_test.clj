(ns hearth.alpha.adapter.ring-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [hearth.alpha.adapter.ring :as ring]
   [hearth.alpha.service :as svc]
   [ti-yong.alpha.async :as async])
  (:import
   [java.util.concurrent CompletableFuture]))

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
      (is (false? (:join? server-cfg)))))

  (testing "create-server supports async? option"
    (let [svc (svc/service
               {:routes [["/hello" :get (fn [_] {:status 200 :body "hi"})
                          :route-name ::hello]]})
          server-cfg (ring/create-server svc {:port 8888 :join? false :async? true})]
      (is (true? (:async? server-cfg))))))

;; Phase 0.5: Async Ring handler tests

(deftest async-handler-sync-route-test
  (testing "3-arity async handler works with sync routes"
    (let [svc (svc/service
               {:routes [["/hello" :get (fn [_] {:status 200 :body "hi"})
                          :route-name ::hello]]})
          handler (ring/service->handler svc)
          response (promise)]
      (handler {:request-method :get :uri "/hello" :headers {}
                :scheme :http :server-name "localhost" :server-port 80}
               #(deliver response %)
               #(deliver response {:error %}))
      (let [resp (deref response 1000 :timeout)]
        (is (not= :timeout resp))
        (is (= 200 (:status resp)))
        (is (= "hi" (:body resp)))))))

(deftest async-handler-deferred-route-test
  (testing "3-arity async handler works with async (deferred) routes"
    (let [svc (svc/service
               {:routes [["/slow" :get
                          (fn [_]
                            (let [cf (CompletableFuture.)]
                              (future
                                (Thread/sleep 10)
                                (.complete cf {:status 200 :body "done"}))
                              cf))
                          :route-name ::slow]]})
          handler (ring/service->handler svc)
          response (promise)]
      (handler {:request-method :get :uri "/slow" :headers {}
                :scheme :http :server-name "localhost" :server-port 80}
               #(deliver response %)
               #(deliver response {:error %}))
      (let [resp (deref response 5000 :timeout)]
        (is (not= :timeout resp))
        (is (= 200 (:status resp)))
        (is (= "done" (:body resp)))))))

(deftest sync-handler-deferred-blocks-test
  (testing "1-arity sync handler blocks on deferred results"
    (let [svc (svc/service
               {:routes [["/slow" :get
                          (fn [_]
                            (CompletableFuture/completedFuture
                             {:status 200 :body "done"}))
                          :route-name ::slow]]})
          handler (ring/service->handler svc)
          resp (handler {:request-method :get :uri "/slow" :headers {}
                         :scheme :http :server-name "localhost" :server-port 80})]
      (is (= 200 (:status resp)))
      (is (= "done" (:body resp))))))
