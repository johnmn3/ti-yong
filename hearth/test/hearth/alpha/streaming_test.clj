(ns hearth.alpha.streaming-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [hearth.alpha.streaming :as streaming])
  (:import
   [java.io ByteArrayOutputStream ByteArrayInputStream]))

(deftest string-body-streaming-test
  (testing "streams a string body to output stream"
    (let [baos (ByteArrayOutputStream.)]
      (streaming/stream-body "Hello, World!" baos)
      (is (= "Hello, World!" (.toString baos "UTF-8"))))))

(deftest input-stream-body-streaming-test
  (testing "streams an InputStream body to output stream"
    (let [data "streaming content"
          bais (ByteArrayInputStream. (.getBytes data "UTF-8"))
          baos (ByteArrayOutputStream.)]
      (streaming/stream-body bais baos)
      (is (= data (.toString baos "UTF-8"))))))

(deftest byte-array-body-streaming-test
  (testing "streams a byte array body to output stream"
    (let [data (.getBytes "byte data" "UTF-8")
          baos (ByteArrayOutputStream.)]
      (streaming/stream-body data baos)
      (is (= "byte data" (.toString baos "UTF-8"))))))

(deftest function-body-streaming-test
  (testing "streams via function body"
    (let [baos (ByteArrayOutputStream.)
          body-fn (fn [^java.io.OutputStream os]
                    (.write os (.getBytes "chunk1" "UTF-8"))
                    (.flush os)
                    (.write os (.getBytes "chunk2" "UTF-8"))
                    (.flush os)
                    (.close os))]
      (streaming/stream-body body-fn baos)
      (is (= "chunk1chunk2" (.toString baos "UTF-8"))))))

(deftest streamable?-test
  (testing "identifies streamable types"
    (is (streaming/streamable? "hello"))
    (is (streaming/streamable? (byte-array 0)))
    (is (streaming/streamable? (fn [os] nil)))
    (is (streaming/streamable? (ByteArrayInputStream. (byte-array 0)))))

  (testing "non-streamable types"
    (is (not (streaming/streamable? 42)))
    (is (not (streaming/streamable? (Object.))))))
