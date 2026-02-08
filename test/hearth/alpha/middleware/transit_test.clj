(ns hearth.alpha.middleware.transit-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [cognitect.transit :as transit]
   [hearth.alpha.middleware :as mw]
   [hearth.alpha.service :as svc])
  (:import
   [java.io ByteArrayInputStream ByteArrayOutputStream]))

(defn- transit-encode [data format]
  (let [baos (ByteArrayOutputStream.)
        writer (transit/writer baos format)]
    (transit/write writer data)
    (.toByteArray baos)))

(defn- transit-decode [bytes format]
  (let [bais (ByteArrayInputStream. bytes)
        reader (transit/reader bais format)]
    (transit/read reader)))

(deftest transit-body-parses-json-test
  (testing "parses Transit+JSON request body into :body-params"
    (let [data {:name "Alice" :age 30}
          body (ByteArrayInputStream. (transit-encode data :json))
          svc (svc/service {:routes [["/test" :post
                                      (fn [env]
                                        {:status 200 :body (pr-str (:body-params env))})
                                      :route-name ::test]]
                             :with [(mw/transit-body)]})
          resp (svc/response-for svc :post "/test"
                 {:headers {"content-type" "application/transit+json"}
                  :body body})]
      (is (= 200 (:status resp)))
      (is (= (pr-str data) (:body resp))))))

(deftest transit-body-parses-msgpack-test
  (testing "parses Transit+MessagePack request body into :body-params"
    (let [data {:x 1 :y [2 3]}
          body (ByteArrayInputStream. (transit-encode data :msgpack))
          svc (svc/service {:routes [["/test" :post
                                      (fn [env]
                                        {:status 200 :body (pr-str (:body-params env))})
                                      :route-name ::test]]
                             :with [(mw/transit-body)]})
          resp (svc/response-for svc :post "/test"
                 {:headers {"content-type" "application/transit+msgpack"}
                  :body body})]
      (is (= 200 (:status resp)))
      (is (= (pr-str data) (:body resp))))))

(deftest transit-body-ignores-other-content-types-test
  (testing "non-transit content types pass through unchanged"
    (let [svc (svc/service {:routes [["/test" :post
                                      (fn [env]
                                        {:status 200 :body (str (nil? (:body-params env)))})
                                      :route-name ::test]]
                             :with [(mw/transit-body)]})
          resp (svc/response-for svc :post "/test"
                 {:headers {"content-type" "text/plain"}
                  :body "hello"})]
      (is (= "true" (:body resp))))))

(deftest transit-json-response-serializes-test
  (testing "serializes response body as Transit+JSON"
    (let [data {:name "Bob" :items [1 2 3]}
          svc (svc/service {:routes [["/test" :get
                                      (fn [_] {:status 200 :body data})
                                      :route-name ::test]]
                             :with [(mw/transit-json-response)]})
          resp (svc/response-for svc :get "/test")]
      (is (= 200 (:status resp)))
      (is (= "application/transit+json" (get-in resp [:headers "Content-Type"])))
      (is (= data (transit-decode (:body resp) :json))))))

(deftest transit-json-response-skips-strings-test
  (testing "string bodies are not serialized"
    (let [svc (svc/service {:routes [["/test" :get
                                      (fn [_] {:status 200 :body "plain text"})
                                      :route-name ::test]]
                             :with [(mw/transit-json-response)]})
          resp (svc/response-for svc :get "/test")]
      (is (= "plain text" (:body resp))))))

(deftest transit-msgpack-response-serializes-test
  (testing "serializes response body as Transit+MessagePack"
    (let [data {:key "value" :nums [10 20]}
          svc (svc/service {:routes [["/test" :get
                                      (fn [_] {:status 200 :body data})
                                      :route-name ::test]]
                             :with [(mw/transit-msgpack-response)]})
          resp (svc/response-for svc :get "/test")]
      (is (= 200 (:status resp)))
      (is (= "application/transit+msgpack" (get-in resp [:headers "Content-Type"])))
      (is (= data (transit-decode (:body resp) :msgpack))))))

(deftest transit-roundtrip-test
  (testing "full roundtrip: Transit+JSON request parsed, response serialized"
    (let [svc (svc/service {:routes [["/echo" :post
                                      (fn [env]
                                        {:status 200
                                         :body (:body-params env)})
                                      :route-name ::echo]]
                             :with [(mw/transit-body) (mw/transit-json-response)]})
          input {:message "hello" :count 42}
          body (ByteArrayInputStream. (transit-encode input :json))
          resp (svc/response-for svc :post "/echo"
                 {:headers {"content-type" "application/transit+json"}
                  :body body})]
      (is (= 200 (:status resp)))
      (is (= input (transit-decode (:body resp) :json))))))
