(ns hearth.alpha.sse-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [hearth.alpha.sse :as sse]))

(deftest event-channel-create-test
  (testing "event-channel returns a map with put!, close!, and queue"
    (let [ch (sse/event-channel)]
      (is (fn? (:put! ch)))
      (is (fn? (:close! ch)))
      (is (some? (:queue ch))))))

(deftest event-channel-put-and-read-test
  (testing "events put on channel are readable from queue"
    (let [ch (sse/event-channel)
          q (:queue ch)]
      ((:put! ch) "hello")
      ((:put! ch) {:event "update" :data "payload"})
      (is (= "hello" (.poll q)))
      (is (= {:event "update" :data "payload"} (.poll q))))))

(deftest event-channel-close-test
  (testing "closing a channel puts a sentinel value"
    (let [ch (sse/event-channel)
          q (:queue ch)]
      ((:close! ch) )
      (is (= :hearth.alpha.sse/closed (.poll q))))))

(deftest event-stream-returns-handler-test
  (testing "event-stream returns a function"
    (let [handler (sse/event-stream (fn [ch env]))]
      (is (fn? handler)))))

(deftest event-stream-response-format-test
  (testing "event-stream handler returns proper SSE response"
    (let [handler (sse/event-stream
                   (fn [ch env]
                     ;; Send one event then close
                     ((:put! ch) "hello")
                     ((:close! ch))))
          resp (handler {:request-method :get :uri "/events"})]
      (is (= 200 (:status resp)))
      (is (= "text/event-stream" (get-in resp [:headers "Content-Type"])))
      (is (= "no-cache, no-store" (get-in resp [:headers "Cache-Control"])))
      (is (fn? (:body resp))))))

(deftest event-stream-writes-events-test
  (testing "body fn writes SSE-formatted events to output stream"
    (let [handler (sse/event-stream
                   (fn [ch env]
                     ((:put! ch) "first")
                     ((:put! ch) {:event "update" :data "second"})
                     ((:close! ch)))
                   {:heartbeat-ms nil}) ;; disable heartbeat for deterministic test
          resp (handler {:request-method :get :uri "/events"})
          baos (java.io.ByteArrayOutputStream.)]
      ;; Run the body fn (it blocks until channel closes)
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
                     ((:close! ch)))
                   {:heartbeat-ms nil
                    :on-close #(reset! closed? true)})
          resp (handler {:request-method :get :uri "/events"})
          baos (java.io.ByteArrayOutputStream.)]
      ((:body resp) baos)
      (is (true? @closed?)))))

(deftest event-stream-custom-headers-test
  (testing "extra headers are merged into SSE response"
    (let [handler (sse/event-stream
                   (fn [ch env] ((:close! ch)))
                   {:headers {"X-Custom" "value"}})
          resp (handler {})]
      (is (= "value" (get-in resp [:headers "X-Custom"])))
      (is (= "text/event-stream" (get-in resp [:headers "Content-Type"]))))))
