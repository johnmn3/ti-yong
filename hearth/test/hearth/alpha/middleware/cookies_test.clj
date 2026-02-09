(ns hearth.alpha.middleware.cookies-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure.string :as str]
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
  (testing "writes Set-Cookie headers from response :cookies as a vector"
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
          (is (vector? set-cookie) "Set-Cookie must be a vector per RFC 6265")
          (is (= 1 (count set-cookie)))
          (let [cookie-str (first set-cookie)]
            (is (re-find #"session=new123" cookie-str))
            (is (re-find #"Path=/" cookie-str))
            (is (re-find #"HttpOnly" cookie-str))))
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
            set-cookie-vec (get-in result [:headers "Set-Cookie"])]
        (is (vector? set-cookie-vec))
        (let [set-cookie (first set-cookie-vec)]
          (is (re-find #"s=v" set-cookie))
          (is (re-find #"Domain=example.com" set-cookie))
          (is (re-find #"Max-Age=3600" set-cookie))
          (is (re-find #"Secure" set-cookie))
          (is (re-find #"HttpOnly" set-cookie))
          (is (re-find #"SameSite=Strict" set-cookie)))))))

(deftest multiple-set-cookie-headers-test
  (testing "multiple cookies produce a vector with one entry per cookie"
    (let [handler (-> t/transformer
                      (update :with conj mw/cookies)
                      (assoc :headers {})
                      (assoc :op (fn [] {:status 200 :headers {} :body "ok"
                                         :cookies {"a" {:value "1" :path "/"}
                                                   "b" {:value "2" :path "/"}}})))]
      (let [result (handler)
            set-cookie-vec (get-in result [:headers "Set-Cookie"])]
        (is (vector? set-cookie-vec))
        (is (= 2 (count set-cookie-vec)))))))

(deftest cookie-special-characters-test
  (testing "cookie values with equals signs are parsed correctly"
    (let [observed (atom nil)
          handler (-> t/transformer
                      (update :with conj mw/cookies)
                      (assoc :headers {"cookie" "token=abc=def=ghi; lang=en"})
                      (assoc :env-op (fn [env]
                                       (reset! observed (:cookies env))
                                       :ok)))]
      (handler)
      ;; The value should contain everything after the first =
      (is (= "abc=def=ghi" (get-in @observed ["token" :value])))))

  (testing "cookie values with spaces are preserved"
    (let [observed (atom nil)
          handler (-> t/transformer
                      (update :with conj mw/cookies)
                      (assoc :headers {"cookie" "msg=hello world"})
                      (assoc :env-op (fn [env]
                                       (reset! observed (:cookies env))
                                       :ok)))]
      (handler)
      (is (= "hello world" (get-in @observed ["msg" :value]))))))
