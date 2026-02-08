(ns hearth.alpha.middleware.session-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [hearth.alpha.middleware :as mw]
   [hearth.alpha.service :as svc]))

(deftest session-round-trip-test
  (testing "session data persists across requests via shared store"
    (let [store (mw/memory-store)
          svc (svc/service
               {:routes [["/set" :get
                          (fn [env]
                            {:status 200 :body "set"
                             :session (assoc (:session env) :user "alice")})
                          :route-name ::set]
                         ["/get" :get
                          (fn [env]
                            {:status 200
                             :body (str (get-in env [:session :user]))})
                          :route-name ::get]]
                :with [mw/cookies (mw/session {:store store})]})
          ;; First request: set session data
          resp1 (svc/response-for svc :get "/set")
          _ (is (= 200 (:status resp1)))
          ;; Extract session cookie from response
          set-cookie (get-in resp1 [:headers "Set-Cookie"])
          _ (is (some? set-cookie))
          session-id (second (re-find #"hearth-session=([^;]+)" set-cookie))
          _ (is (some? session-id))
          ;; Second request: read session data
          resp2 (svc/response-for svc :get "/get"
                  {:headers {"cookie" (str "hearth-session=" session-id)}})]
      (is (= 200 (:status resp2)))
      (is (= "alice" (:body resp2))))))

(deftest session-custom-cookie-name-test
  (testing "session uses custom cookie name"
    (let [store (mw/memory-store)
          svc (svc/service
               {:routes [["/set" :get
                          (fn [env]
                            {:status 200 :body "ok"
                             :session {:data true}})
                          :route-name ::set]]
                :with [mw/cookies (mw/session {:store store
                                               :cookie-name "my-session"})]})
          resp (svc/response-for svc :get "/set")
          set-cookie (get-in resp [:headers "Set-Cookie"])]
      (is (re-find #"my-session=" set-cookie)))))

(deftest memory-store-test
  (testing "memory store basic operations"
    (let [store (mw/memory-store)]
      (is (nil? (mw/read-session store "nonexistent")))
      (mw/write-session store "key1" {:user "bob"})
      (is (= {:user "bob"} (mw/read-session store "key1")))
      (mw/delete-session store "key1")
      (is (nil? (mw/read-session store "key1"))))))
