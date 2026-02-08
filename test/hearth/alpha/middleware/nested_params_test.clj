(ns hearth.alpha.middleware.nested-params-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [hearth.alpha.middleware :as mw]
   [ti-yong.alpha.transformer :as t]))

(deftest nested-query-params-test
  (testing "nests bracket-notation query params"
    (let [observed (atom nil)
          handler (-> t/transformer
                      (update :with conj mw/query-params mw/nested-params)
                      (assoc :query-string "user[name]=Alice&user[age]=30")
                      (assoc :env-op (fn [env]
                                       (reset! observed (:query-params env))
                                       :ok)))]
      (handler)
      (is (= {"user" {"name" "Alice" "age" "30"}} @observed)))))

(deftest nested-form-params-test
  (testing "nests bracket-notation form params"
    (let [observed (atom nil)
          handler (-> t/transformer
                      (update :with conj mw/form-params mw/nested-params)
                      (assoc :headers {"content-type" "application/x-www-form-urlencoded"})
                      (assoc :body "item[name]=Widget&item[price]=10")
                      (assoc :env-op (fn [env]
                                       (reset! observed (:form-params env))
                                       :ok)))]
      (handler)
      (is (= {"item" {"name" "Widget" "price" "10"}} @observed)))))

(deftest nested-params-no-brackets-test
  (testing "params without brackets pass through unchanged"
    (let [observed (atom nil)
          handler (-> t/transformer
                      (update :with conj mw/query-params mw/nested-params)
                      (assoc :query-string "name=Alice&age=30")
                      (assoc :env-op (fn [env]
                                       (reset! observed (:query-params env))
                                       :ok)))]
      (handler)
      (is (= {"name" "Alice" "age" "30"} @observed)))))

(deftest deeply-nested-params-test
  (testing "deeply nested bracket notation"
    (let [observed (atom nil)
          handler (-> t/transformer
                      (update :with conj mw/query-params mw/nested-params)
                      (assoc :query-string "a[b][c]=deep")
                      (assoc :env-op (fn [env]
                                       (reset! observed (:query-params env))
                                       :ok)))]
      (handler)
      (is (= {"a" {"b" {"c" "deep"}}} @observed)))))
