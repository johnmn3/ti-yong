(ns hearth.alpha.middleware.sse-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [hearth.alpha.middleware :as mw]))

(deftest format-sse-simple-data-test
  (testing "formats simple string data"
    (is (= "data: hello\n\n"
           (mw/format-sse-event "hello")))))

(deftest format-sse-event-map-test
  (testing "formats event map with all fields"
    (let [result (mw/format-sse-event {:id "1"
                                       :event "message"
                                       :retry 5000
                                       :data "test data"})]
      (is (re-find #"id: 1\n" result))
      (is (re-find #"event: message\n" result))
      (is (re-find #"retry: 5000\n" result))
      (is (re-find #"data: test data\n\n" result)))))

(deftest format-sse-data-only-test
  (testing "formats event map with data only"
    (is (= "data: just data\n\n"
           (mw/format-sse-event {:data "just data"})))))

(deftest format-sse-with-event-name-test
  (testing "formats event with named event"
    (let [result (mw/format-sse-event {:event "update" :data "payload"})]
      (is (= "event: update\ndata: payload\n\n" result)))))
