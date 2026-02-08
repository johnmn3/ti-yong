(ns hearth.middleware-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [hearth.middleware :as mw]
   [ti-yong.alpha.transformer :as t]))

;; Phase 2: Middleware as transformer mixins
;; Middleware are transformers composed via :with.
;; - :tf steps modify the env (parse request data into env keys)
;; - :out steps transform the result value directly
;; - :tf-end steps modify the final env; since transformer-invoke returns
;;   (:res end-env), middleware that needs to modify the response should
;;   update :res in the env during :tf-end.

(deftest query-params-middleware-test
  (testing "query-params parses query string into :query-params map"
    (let [observed (atom nil)
          handler (-> t/transformer
                      (update :with conj mw/query-params)
                      (assoc :query-string "name=alice&age=30")
                      (assoc :env-op (fn [env]
                                       (reset! observed (:query-params env))
                                       :ok)))]
      (handler)
      (is (= {"name" "alice" "age" "30"} @observed))))

  (testing "query-params with no query string"
    (let [observed (atom nil)
          handler (-> t/transformer
                      (update :with conj mw/query-params)
                      (assoc :env-op (fn [env]
                                       (reset! observed (:query-params env))
                                       :ok)))]
      (handler)
      (is (= {} @observed))))

  (testing "query-params with empty query string"
    (let [observed (atom nil)
          handler (-> t/transformer
                      (update :with conj mw/query-params)
                      (assoc :query-string "")
                      (assoc :env-op (fn [env]
                                       (reset! observed (:query-params env))
                                       :ok)))]
      (handler)
      (is (= {} @observed)))))

(deftest json-body-middleware-test
  (testing "json-body parses JSON string body into Clojure data"
    (let [observed (atom nil)
          handler (-> t/transformer
                      (update :with conj mw/json-body)
                      (assoc :body "{\"name\":\"alice\",\"age\":30}")
                      (assoc :headers {"content-type" "application/json"})
                      (assoc :env-op (fn [env]
                                       (reset! observed (:json-body env))
                                       :ok)))]
      (handler)
      (is (= {"name" "alice" "age" 30} @observed))))

  (testing "json-body with non-JSON content-type leaves :json-body nil"
    (let [observed (atom nil)
          handler (-> t/transformer
                      (update :with conj mw/json-body)
                      (assoc :body "plain text"
                             :headers {"content-type" "text/plain"})
                      (assoc :env-op (fn [env]
                                       (reset! observed (:json-body env))
                                       :ok)))]
      (handler)
      (is (nil? @observed)))))

(deftest json-response-middleware-test
  (testing "json-response serializes map results to JSON string"
    (let [handler (-> t/transformer
                      (update :with conj mw/json-response)
                      (assoc :op (fn [] {:name "alice" :age 30})))]
      (let [result (handler)]
        (is (string? result))
        (is (re-find #"\"name\"" result)))))

  (testing "json-response passes through non-map results"
    (let [handler (-> t/transformer
                      (update :with conj mw/json-response)
                      (assoc :op (fn [] "plain string")))]
      (is (= "plain string" (handler))))))

(deftest logging-middleware-test
  (testing "logging middleware captures log entries"
    (let [log (atom [])
          handler (-> t/transformer
                      (update :with conj (mw/logging log))
                      (assoc :request-method :get
                             :uri "/test"
                             :op (fn [] :ok)))]
      (handler)
      (is (pos? (count @log)))
      (is (some #(and (:method %) (:uri %)) @log)))))

(deftest content-type-middleware-test
  (testing "content-type middleware sets Content-Type on response map in :res"
    ;; Handler :op returns a Ring response map.
    ;; The middleware's :tf-end updates :res to add Content-Type.
    (let [handler (-> t/transformer
                      (update :with conj (mw/default-content-type "application/json"))
                      (assoc :op (fn [] {:status 200 :headers {} :body "hello"})))]
      (let [result (handler)]
        (is (= "application/json" (get-in result [:headers "Content-Type"]))))))

  (testing "content-type middleware does not overwrite existing Content-Type"
    (let [handler (-> t/transformer
                      (update :with conj (mw/default-content-type "application/json"))
                      (assoc :op (fn [] {:status 200
                                         :headers {"Content-Type" "text/html"}
                                         :body "<h1>hi</h1>"})))]
      (let [result (handler)]
        (is (= "text/html" (get-in result [:headers "Content-Type"])))))))

(deftest cors-middleware-test
  (testing "cors middleware adds CORS headers to response map in :res"
    (let [handler (-> t/transformer
                      (update :with conj (mw/cors {:allowed-origins "*"
                                                    :allowed-methods "GET, POST"
                                                    :allowed-headers "Content-Type"}))
                      (assoc :op (fn [] {:status 200 :headers {} :body "ok"})))]
      (let [result (handler)]
        (is (= "*" (get-in result [:headers "Access-Control-Allow-Origin"])))
        (is (= "GET, POST" (get-in result [:headers "Access-Control-Allow-Methods"])))
        (is (= "Content-Type" (get-in result [:headers "Access-Control-Allow-Headers"])))))))

(deftest middleware-composition-test
  (testing "multiple middleware compose via :with"
    (let [observed (atom {})
          handler (-> t/transformer
                      (update :with conj mw/query-params mw/json-body)
                      (assoc :query-string "page=1"
                             :body "{\"item\":\"test\"}"
                             :headers {"content-type" "application/json"})
                      (assoc :env-op (fn [env]
                                       (reset! observed
                                               {:query-params (:query-params env)
                                                :json-body (:json-body env)})
                                       :ok)))]
      (handler)
      (is (= {"page" "1"} (:query-params @observed)))
      (is (= {"item" "test"} (:json-body @observed))))))

(deftest not-found-middleware-test
  (testing "not-found middleware returns 404 response when :res is nil"
    (let [handler (-> t/transformer
                      (update :with conj (mw/not-found-handler "Not Found"))
                      (assoc :op (fn [] nil)))]
      (let [result (handler)]
        (is (= 404 (:status result)))
        (is (= "Not Found" (:body result))))))

  (testing "not-found middleware passes through non-nil :res"
    (let [handler (-> t/transformer
                      (update :with conj (mw/not-found-handler "Not Found"))
                      (assoc :op (fn [] {:status 200 :body "found"})))]
      (let [result (handler)]
        (is (= {:status 200 :body "found"} result))))))
