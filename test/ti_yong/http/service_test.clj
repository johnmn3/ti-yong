(ns ti-yong.http.service-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [ti-yong.http.service :as svc]
   [ti-yong.http.middleware :as mw]
   [ti-yong.http.error :as err]
   [ti-yong.alpha.transformer :as t]))

;; Phase 5: Service transformer
;; The service-tf is the root transformer that combines:
;; - A router (from route definitions)
;; - Global middleware (applied to all requests)
;; - Error handling

(deftest service-transformer-test
  (testing "service-tf is a transformer"
    (let [svc (svc/service
               {:routes [["/hello" :get (fn [_] {:status 200 :body "hi"})
                          :route-name ::hello]]})]
      (is (vector? (:id svc)))
      (is (some #(= ::svc/service %) (:id svc)))))

  (testing "service-tf dispatches to routes"
    (let [svc (svc/service
               {:routes [["/hello" :get (fn [_] {:status 200 :body "hi"})
                          :route-name ::hello]
                         ["/bye" :get (fn [_] {:status 200 :body "bye"})
                          :route-name ::bye]]})]
      (let [result (-> svc
                       (assoc :request-method :get :uri "/hello")
                       (apply []))]
        (is (= {:status 200 :body "hi"} result)))
      (let [result (-> svc
                       (assoc :request-method :get :uri "/bye")
                       (apply []))]
        (is (= {:status 200 :body "bye"} result))))))

(deftest service-with-global-middleware-test
  (testing "global middleware applies to all routes"
    (let [log (atom [])
          svc (svc/service
               {:routes [["/hello" :get (fn [_] {:status 200 :body "hi"})
                          :route-name ::hello]]
                :with [(mw/logging log)]})]
      (-> svc
          (assoc :request-method :get :uri "/hello")
          (apply []))
      (is (pos? (count @log)))))

  (testing "global error handler catches route exceptions"
    (let [svc (svc/service
               {:routes [["/boom" :get (fn [_] (throw (ex-info "kaboom" {})))
                          :route-name ::boom]]
                :with [err/error-handler]})]
      (let [result (-> svc
                       (assoc :request-method :get :uri "/boom")
                       (apply []))]
        (is (= 500 (:status result)))
        (is (re-find #"kaboom" (:body result)))))))

(deftest service-404-test
  (testing "service returns 404 for unmatched routes"
    (let [svc (svc/service
               {:routes [["/hello" :get (fn [_] {:status 200 :body "hi"})
                          :route-name ::hello]]})]
      (let [result (-> svc
                       (assoc :request-method :get :uri "/nonexistent")
                       (apply []))]
        (is (= 404 (:status result)))))))

(deftest response-for-test
  (testing "response-for is a convenience to test service with method+path"
    (let [svc (svc/service
               {:routes [["/items" :get (fn [_] {:status 200 :body "items"})
                          :route-name ::items]
                         ["/items" :post (fn [env] {:status 201 :body "created"})
                          :route-name ::create-item]]})]
      (is (= {:status 200 :body "items"}
             (svc/response-for svc :get "/items")))
      (is (= {:status 201 :body "created"}
             (svc/response-for svc :post "/items")))
      (is (= 404 (:status (svc/response-for svc :delete "/items")))))))

(deftest response-for-with-extras-test
  (testing "response-for passes extra request data"
    (let [observed (atom nil)
          svc (svc/service
               {:routes [["/search" :get
                          (fn [env]
                            (reset! observed (:query-string env))
                            {:status 200 :body "ok"})
                          :route-name ::search]]})]
      (svc/response-for svc :get "/search" {:query-string "q=test"})
      (is (= "q=test" @observed)))))
