(ns hearth.alpha.middleware.cookies-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [hearth.alpha.middleware :as mw]
   [ti-yong.alpha.transformer :as t]))

(deftest parse-cookies-test
  (testing "parses Cookie header into :cookies map"
    (let [observed (atom nil)
          handler (-> t/transformer
                      (update :with conj mw/cookies)
                      (assoc :headers {"cookie" "session=abc123; lang=en"})
                      (assoc :env-op (fn [env]
                                       (reset! observed (:cookies env))
                                       :ok)))]
      (handler)
      (is (= {"session" {:value "abc123"} "lang" {:value "en"}} @observed))))

  (testing "empty cookie header yields empty map"
    (let [observed (atom nil)
          handler (-> t/transformer
                      (update :with conj mw/cookies)
                      (assoc :headers {})
                      (assoc :env-op (fn [env]
                                       (reset! observed (:cookies env))
                                       :ok)))]
      (handler)
      (is (= {} @observed)))))

(deftest set-cookies-test
  (testing "writes Set-Cookie headers from response :cookies"
    (let [handler (-> t/transformer
                      (update :with conj mw/cookies)
                      (assoc :headers {})
                      (assoc :op (fn [] {:status 200 :headers {}
                                         :body "ok"
                                         :cookies {"session" {:value "new123"
                                                              :path "/"
                                                              :http-only true}}})))]
      (let [result (handler)]
        (is (map? result))
        (is (= 200 (:status result)))
        (let [set-cookie (get-in result [:headers "Set-Cookie"])]
          (is (some? set-cookie))
          (is (re-find #"session=new123" set-cookie))
          (is (re-find #"Path=/" set-cookie))
          (is (re-find #"HttpOnly" set-cookie)))
        ;; :cookies should be removed from response body
        (is (nil? (:cookies result)))))))

(deftest cookie-attributes-test
  (testing "serializes all cookie attributes"
    (let [handler (-> t/transformer
                      (update :with conj mw/cookies)
                      (assoc :headers {})
                      (assoc :op (fn [] {:status 200 :headers {} :body "ok"
                                         :cookies {"s" {:value "v"
                                                        :path "/"
                                                        :domain "example.com"
                                                        :max-age 3600
                                                        :secure true
                                                        :http-only true
                                                        :same-site "Strict"}}})))]
      (let [result (handler)
            set-cookie (get-in result [:headers "Set-Cookie"])]
        (is (re-find #"s=v" set-cookie))
        (is (re-find #"Domain=example.com" set-cookie))
        (is (re-find #"Max-Age=3600" set-cookie))
        (is (re-find #"Secure" set-cookie))
        (is (re-find #"HttpOnly" set-cookie))
        (is (re-find #"SameSite=Strict" set-cookie))))))
