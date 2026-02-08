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
