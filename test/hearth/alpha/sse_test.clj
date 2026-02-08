(ns hearth.alpha.sse-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure.core.async :as a]
   [hearth.alpha.sse :as sse]))

(deftest event-stream-returns-handler-test
  (testing "event-stream returns a function"
    (let [handler (sse/event-stream (fn [ch env]))]
      (is (fn? handler)))))

(deftest event-stream-response-format-test
  (testing "event-stream handler returns proper SSE response"
    (let [handler (sse/event-stream
                   (fn [ch env]
                     (a/close! ch)))
          resp (handler {:request-method :get :uri "/events"})]
      (is (= 200 (:status resp)))
      (is (= "text/event-stream" (get-in resp [:headers "Content-Type"])))
      (is (= "no-cache, no-store" (get-in resp [:headers "Cache-Control"])))
      (is (fn? (:body resp))))))

(deftest event-stream-writes-events-test
  (testing "body fn writes SSE-formatted events to output stream"
    (let [handler (sse/event-stream
                   (fn [ch env]
                     (a/>!! ch "first")
                     (a/>!! ch {:event "update" :data "second"})
                     (a/close! ch))
                   {:heartbeat-ms nil})
          resp (handler {:request-method :get :uri "/events"})
          baos (java.io.ByteArrayOutputStream.)]
      ((:body resp) baos)
      (let [output (.toString baos "UTF-8")]
        (is (clojure.string/includes? output "data: first\n\n"))
        (is (clojure.string/includes? output "event: update\n"))
        (is (clojure.string/includes? output "data: second\n\n"))))))

(deftest event-stream-on-close-callback-test
  (testing "on-close callback is invoked when stream ends"
    (let [closed? (atom false)
          handler (sse/event-stream
                   (fn [ch env]
                     (a/close! ch))
                   {:heartbeat-ms nil
                    :on-close #(reset! closed? true)})
          resp (handler {:request-method :get :uri "/events"})
          baos (java.io.ByteArrayOutputStream.)]
      ((:body resp) baos)
      (is (true? @closed?)))))

(deftest event-stream-custom-headers-test
  (testing "extra headers are merged into SSE response"
    (let [handler (sse/event-stream
                   (fn [ch env] (a/close! ch))
                   {:headers {"X-Custom" "value"}})
          resp (handler {})]
      (is (= "value" (get-in resp [:headers "X-Custom"])))
      (is (= "text/event-stream" (get-in resp [:headers "Content-Type"]))))))

(deftest event-stream-multiple-events-order-test
  (testing "events are delivered in order"
    (let [handler (sse/event-stream
                   (fn [ch env]
                     (dotimes [i 5]
                       (a/>!! ch (str "event-" i)))
                     (a/close! ch))
                   {:heartbeat-ms nil})
          resp (handler {})
          baos (java.io.ByteArrayOutputStream.)]
      ((:body resp) baos)
      (let [output (.toString baos "UTF-8")]
        (dotimes [i 5]
          (is (clojure.string/includes? output (str "data: event-" i "\n\n"))))))))
