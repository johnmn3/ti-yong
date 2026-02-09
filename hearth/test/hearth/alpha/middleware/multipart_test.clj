(ns hearth.alpha.middleware.multipart-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [hearth.alpha.middleware :as mw]
   [ti-yong.alpha.transformer :as t]))

(deftest multipart-params-text-fields-test
  (testing "parses text form fields from multipart body"
    (let [boundary "----boundary123"
          body (str "------boundary123\r\n"
                    "Content-Disposition: form-data; name=\"name\"\r\n\r\n"
                    "Alice\r\n"
                    "------boundary123\r\n"
                    "Content-Disposition: form-data; name=\"age\"\r\n\r\n"
                    "30\r\n"
                    "------boundary123--\r\n")
          observed (atom nil)
          handler (-> t/transformer
                      (update :with conj (mw/multipart-params))
                      (assoc :headers {"content-type"
                                       (str "multipart/form-data; boundary=----boundary123")})
                      (assoc :body body)
                      (assoc :env-op (fn [env]
                                       (reset! observed (:multipart-params env))
                                       :ok)))]
      (handler)
      (is (some? @observed))
      (is (= "Alice" (get @observed "name")))
      (is (= "30" (get @observed "age"))))))

(deftest multipart-params-file-upload-test
  (testing "parses file upload from multipart body"
    (let [boundary "----boundary456"
          body (str "------boundary456\r\n"
                    "Content-Disposition: form-data; name=\"file\"; filename=\"test.txt\"\r\n"
                    "Content-Type: text/plain\r\n\r\n"
                    "Hello file content\r\n"
                    "------boundary456--\r\n")
          observed (atom nil)
          handler (-> t/transformer
                      (update :with conj (mw/multipart-params))
                      (assoc :headers {"content-type"
                                       (str "multipart/form-data; boundary=----boundary456")})
                      (assoc :body body)
                      (assoc :env-op (fn [env]
                                       (reset! observed (:multipart-params env))
                                       :ok)))]
      (handler)
      (is (some? @observed))
      (let [file-entry (get @observed "file")]
        (is (map? file-entry))
        (is (= "test.txt" (:filename file-entry)))
        (is (= "text/plain" (:content-type file-entry)))
        (is (some? (:bytes file-entry)))))))

(deftest multipart-ignores-non-multipart-test
  (testing "non-multipart content type leaves env unchanged"
    (let [observed (atom nil)
          handler (-> t/transformer
                      (update :with conj (mw/multipart-params))
                      (assoc :headers {"content-type" "application/json"})
                      (assoc :body "{}")
                      (assoc :env-op (fn [env]
                                       (reset! observed (:multipart-params env))
                                       :ok)))]
      (handler)
      (is (nil? @observed)))))

(deftest multipart-binary-file-preserved-test
  (testing "binary content is preserved without corruption"
    (let [;; Create bytes 0x00-0xFF to test full byte range
          test-bytes (byte-array (range 256))
          boundary "----binarytest"
          ;; Build multipart body as raw bytes
          header-str (str "------binarytest\r\n"
                          "Content-Disposition: form-data; name=\"file\"; filename=\"test.bin\"\r\n"
                          "Content-Type: application/octet-stream\r\n\r\n")
          footer-str "\r\n------binarytest--\r\n"
          header-bytes (.getBytes ^String header-str "UTF-8")
          footer-bytes (.getBytes ^String footer-str "UTF-8")
          ;; Concatenate header + binary content + footer
          body-bytes (let [baos (java.io.ByteArrayOutputStream.)]
                       (.write baos header-bytes)
                       (.write baos test-bytes)
                       (.write baos footer-bytes)
                       (.toByteArray baos))
          observed (atom nil)
          handler (-> t/transformer
                      (update :with conj (mw/multipart-params))
                      (assoc :headers {"content-type"
                                       "multipart/form-data; boundary=----binarytest"})
                      (assoc :body body-bytes)
                      (assoc :env-op (fn [env]
                                       (reset! observed (:multipart-params env))
                                       :ok)))]
      (handler)
      (is (some? @observed))
      (let [file-entry (get @observed "file")]
        (is (= 256 (:size file-entry)))
        (is (java.util.Arrays/equals test-bytes ^bytes (:bytes file-entry)))))))

(deftest multipart-max-size-enforced-test
  (testing "body exceeding max-size returns 413"
    (let [boundary "----sizelimit"
          body (str "------sizelimit\r\n"
                    "Content-Disposition: form-data; name=\"data\"\r\n\r\n"
                    (apply str (repeat 200 "A"))
                    "\r\n------sizelimit--\r\n")
          handler (-> t/transformer
                      (update :with conj (mw/multipart-params {:max-size 100}))
                      (assoc :headers {"content-type"
                                       "multipart/form-data; boundary=----sizelimit"})
                      (assoc :body body))]
      (let [result (handler)]
        (is (= 413 (:status result)))))))

(deftest multipart-multiple-files-test
  (testing "parses multiple file fields"
    (let [boundary "----multi"
          body (str "------multi\r\n"
                    "Content-Disposition: form-data; name=\"file1\"; filename=\"a.txt\"\r\n"
                    "Content-Type: text/plain\r\n\r\n"
                    "content-a\r\n"
                    "------multi\r\n"
                    "Content-Disposition: form-data; name=\"file2\"; filename=\"b.txt\"\r\n"
                    "Content-Type: text/plain\r\n\r\n"
                    "content-b\r\n"
                    "------multi--\r\n")
          observed (atom nil)
          handler (-> t/transformer
                      (update :with conj (mw/multipart-params))
                      (assoc :headers {"content-type"
                                       "multipart/form-data; boundary=----multi"})
                      (assoc :body body)
                      (assoc :env-op (fn [env]
                                       (reset! observed (:multipart-params env))
                                       :ok)))]
      (handler)
      (is (= "a.txt" (get-in @observed ["file1" :filename])))
      (is (= "b.txt" (get-in @observed ["file2" :filename]))))))
