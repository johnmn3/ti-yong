(ns hearth.alpha.middleware.csrf-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [hearth.alpha.middleware :as mw]
   [hearth.alpha.service :as svc]))

(defn- extract-session-id
  "Extract session ID from Set-Cookie header (vector or string)."
  [set-cookie]
  (let [cookies (cond
                  (vector? set-cookie) set-cookie
                  (string? set-cookie) [set-cookie]
                  :else [])]
    (some #(second (re-find #"hearth-session=([^;]+)" %)) cookies)))

(deftest csrf-blocks-unsafe-without-token-test
  (testing "POST without CSRF token returns 403"
    (let [store (mw/memory-store)
          svc (svc/service
               {:routes [["/submit" :post
                          (fn [_] {:status 200 :body "ok"})
                          :route-name ::submit]]
                :with [mw/cookies (mw/session {:store store})
                       mw/query-params (mw/csrf)]})
          resp (svc/response-for svc :post "/submit")]
      (is (= 403 (:status resp))))))

(deftest csrf-allows-safe-methods-test
  (testing "GET requests pass through and get a CSRF token"
    (let [store (mw/memory-store)
          svc (svc/service
               {:routes [["/form" :get
                          (fn [env]
                            {:status 200 :body (str (:csrf-token env))})
                          :route-name ::form]]
                :with [mw/cookies (mw/session {:store store}) (mw/csrf)]})
          resp (svc/response-for svc :get "/form")]
      (is (= 200 (:status resp)))
      ;; Should have a CSRF token in the body (returned by handler)
      (is (not (clojure.string/blank? (:body resp)))))))

(deftest csrf-allows-valid-token-test
  (testing "POST with valid CSRF token succeeds"
    (let [store (mw/memory-store)
          svc (svc/service
               {:routes [["/form" :get
                          (fn [env] {:status 200 :body (str (:csrf-token env))})
                          :route-name ::form]
                         ["/submit" :post
                          (fn [_] {:status 200 :body "submitted"})
                          :route-name ::submit]]
                :with [mw/cookies (mw/session {:store store})
                       mw/query-params (mw/csrf)]})
          ;; Step 1: GET to obtain CSRF token and session
          get-resp (svc/response-for svc :get "/form")
          token (:body get-resp)
          set-cookie (get-in get-resp [:headers "Set-Cookie"])
          session-id (extract-session-id set-cookie)
          ;; Step 2: POST with valid token via header
          post-resp (svc/response-for svc :post "/submit"
                      {:headers {"cookie" (str "hearth-session=" session-id)
                                 "x-csrf-token" token}})]
      (is (= 200 (:status post-resp)))
      (is (= "submitted" (:body post-resp))))))

(deftest csrf-rejects-wrong-token-test
  (testing "POST with wrong CSRF token returns 403"
    (let [store (mw/memory-store)
          svc (svc/service
               {:routes [["/form" :get
                          (fn [env] {:status 200 :body (str (:csrf-token env))})
                          :route-name ::form]
                         ["/submit" :post
                          (fn [_] {:status 200 :body "submitted"})
                          :route-name ::submit]]
                :with [mw/cookies (mw/session {:store store})
                       mw/query-params (mw/csrf)]})
          ;; GET to establish session
          get-resp (svc/response-for svc :get "/form")
          set-cookie (get-in get-resp [:headers "Set-Cookie"])
          session-id (extract-session-id set-cookie)
          ;; POST with wrong token
          post-resp (svc/response-for svc :post "/submit"
                      {:headers {"cookie" (str "hearth-session=" session-id)
                                 "x-csrf-token" "wrong-token"}})]
      (is (= 403 (:status post-resp))))))

(deftest csrf-blocks-put-without-token-test
  (testing "PUT without CSRF token returns 403"
    (let [store (mw/memory-store)
          svc (svc/service
               {:routes [["/update" :put
                          (fn [_] {:status 200 :body "ok"})
                          :route-name ::update]]
                :with [mw/cookies (mw/session {:store store})
                       mw/query-params (mw/csrf)]})
          resp (svc/response-for svc :put "/update")]
      (is (= 403 (:status resp))))))

(deftest csrf-blocks-delete-without-token-test
  (testing "DELETE without CSRF token returns 403"
    (let [store (mw/memory-store)
          svc (svc/service
               {:routes [["/remove" :delete
                          (fn [_] {:status 200 :body "ok"})
                          :route-name ::remove]]
                :with [mw/cookies (mw/session {:store store})
                       mw/query-params (mw/csrf)]})
          resp (svc/response-for svc :delete "/remove")]
      (is (= 403 (:status resp))))))
