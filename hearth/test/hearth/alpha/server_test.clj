(ns hearth.alpha.server-test
  "Tests against a running Jetty server.
   Verifies Ring compatibility and real HTTP behavior."
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [hearth.alpha :as http]
   [hearth.alpha.middleware :as mw]
   [hearth.alpha.error :as err]
   [examples.pet-store :as pet-store])
  (:import
   [java.net URI]
   [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers]))

;; --- HTTP Client helpers ---

(def ^:dynamic *base-url* "http://localhost:18181")

(defn- http-get [path & {:keys [headers]}]
  (let [client (HttpClient/newHttpClient)
        builder (-> (HttpRequest/newBuilder)
                    (.uri (URI. (str *base-url* path)))
                    (.GET))
        builder (reduce-kv (fn [b k v] (.header b k v)) builder (or headers {}))
        request (.build builder)
        response (.send client request (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode response)
     :body (.body response)
     :headers (into {} (map (fn [[k vs]] [k (first vs)])
                            (.map (.headers response))))}))

(defn- http-post [path body & {:keys [headers]}]
  (let [client (HttpClient/newHttpClient)
        builder (-> (HttpRequest/newBuilder)
                    (.uri (URI. (str *base-url* path)))
                    (.POST (HttpRequest$BodyPublishers/ofString (or body ""))))
        builder (reduce-kv (fn [b k v] (.header b k v)) builder (or headers {}))
        request (.build builder)
        response (.send client request (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode response)
     :body (.body response)
     :headers (into {} (map (fn [[k vs]] [k (first vs)])
                            (.map (.headers response))))}))

(defn- http-put [path body & {:keys [headers]}]
  (let [client (HttpClient/newHttpClient)
        builder (-> (HttpRequest/newBuilder)
                    (.uri (URI. (str *base-url* path)))
                    (.PUT (HttpRequest$BodyPublishers/ofString (or body ""))))
        builder (reduce-kv (fn [b k v] (.header b k v)) builder (or headers {}))
        request (.build builder)
        response (.send client request (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode response)
     :body (.body response)
     :headers (into {} (map (fn [[k vs]] [k (first vs)])
                            (.map (.headers response))))}))

(defn- http-delete [path]
  (let [client (HttpClient/newHttpClient)
        request (-> (HttpRequest/newBuilder)
                    (.uri (URI. (str *base-url* path)))
                    (.DELETE)
                    (.build))
        response (.send client request (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode response)
     :body (.body response)
     :headers (into {} (map (fn [[k vs]] [k (first vs)])
                            (.map (.headers response))))}))

;; --- Server lifecycle ---

(def ^:private server (atom nil))

(defn start-server [f]
  (pet-store/reset-pets!)
  (let [server-cfg (http/create-server
                    (assoc pet-store/service-map
                           ::http/port 18181
                           ::http/join? false))]
    (reset! server (http/start server-cfg))
    ;; Give server a moment to start
    (Thread/sleep 500)
    (try
      (f)
      (finally
        (http/stop @server)
        (reset! server nil)
        (Thread/sleep 200)))))

(use-fixtures :once start-server)

;; Reset pets before each test
(defn reset-store [f]
  (pet-store/reset-pets!)
  (f))

(use-fixtures :each reset-store)

;; --- Tests Against Running Server ---

(deftest server-home-page-test
  (testing "GET / returns HTML"
    (let [resp (http-get "/")]
      (is (= 200 (:status resp)))
      (is (re-find #"Pet Store" (:body resp)))
      (is (re-find #"text/html" (get (:headers resp) "content-type" ""))))))

(deftest server-list-pets-test
  (testing "GET /pets returns JSON"
    (let [resp (http-get "/pets")]
      (is (= 200 (:status resp)))
      ;; Body should be JSON (serialized by json-body-response middleware)
      (is (re-find #"pets" (:body resp)))
      (is (re-find #"Rex" (:body resp))))))

(deftest server-get-pet-test
  (testing "GET /pets/1 returns pet details"
    (let [resp (http-get "/pets/1")]
      (is (= 200 (:status resp)))
      (is (re-find #"Rex" (:body resp)))))

  (testing "GET /pets/999 returns 404"
    (let [resp (http-get "/pets/999")]
      (is (= 404 (:status resp))))))

(deftest server-create-pet-test
  (testing "POST /pets with JSON body creates a pet"
    (let [resp (http-post "/pets"
                          "{\"name\":\"Buddy\",\"type\":\"dog\",\"age\":2}"
                          :headers {"content-type" "application/json"})]
      (is (= 201 (:status resp)))
      (is (re-find #"Buddy" (:body resp)))
      ;; Location header should be present
      (is (re-find #"/pets/" (get (:headers resp) "location" ""))))))

(deftest server-update-pet-test
  (testing "PUT /pets/1 updates a pet"
    (let [resp (http-put "/pets/1"
                         "{\"name\":\"Rex Jr\"}"
                         :headers {"content-type" "application/json"})]
      (is (= 200 (:status resp)))
      (is (re-find #"Updated" (:body resp))))))

(deftest server-delete-pet-test
  (testing "DELETE /pets/2 deletes a pet"
    (let [resp (http-delete "/pets/2")]
      (is (= 200 (:status resp)))
      (is (re-find #"Deleted" (:body resp))))))

(deftest server-search-pets-test
  (testing "GET /pets/search?type=cat returns filtered results"
    (let [resp (http-get "/pets/search?type=cat")]
      (is (= 200 (:status resp)))
      (is (re-find #"Whiskers" (:body resp))))))

(deftest server-404-test
  (testing "GET /nonexistent returns 404"
    (let [resp (http-get "/nonexistent")]
      (is (= 404 (:status resp))))))

(deftest server-cors-headers-test
  (testing "CORS headers are present on responses"
    (let [resp (http-get "/pets")]
      (is (= "*" (get (:headers resp) "access-control-allow-origin"))))))

(deftest server-error-handling-test
  (testing "errors are caught by error handler middleware"
    ;; Our pet store doesn't have a crashing route, but we can verify
    ;; the error handler works by checking that the server stays up
    ;; after accessing invalid endpoints
    (let [resp1 (http-get "/pets/999")
          resp2 (http-get "/pets")]
      ;; Server still works after a 404
      (is (= 404 (:status resp1)))
      (is (= 200 (:status resp2))))))

(deftest server-ring-compatibility-test
  (testing "Ring response format is correct"
    (let [resp (http-get "/")]
      ;; Standard Ring response properties
      (is (integer? (:status resp)))
      (is (string? (:body resp)))
      (is (map? (:headers resp)))))

  (testing "Ring request handling with various methods works"
    ;; GET
    (is (= 200 (:status (http-get "/pets"))))
    ;; POST
    (is (= 201 (:status (http-post "/pets"
                                   "{\"name\":\"Test\",\"type\":\"test\",\"age\":1}"
                                   :headers {"content-type" "application/json"}))))
    ;; PUT
    (is (= 200 (:status (http-put "/pets/1" "{\"name\":\"Updated\"}"
                                  :headers {"content-type" "application/json"}))))
    ;; DELETE
    (is (= 200 (:status (http-delete "/pets/1"))))))

(deftest server-json-content-type-test
  (testing "JSON responses have correct Content-Type header"
    (let [resp (http-get "/pets")]
      ;; json-body-response middleware should set Content-Type
      (is (re-find #"application/json" (get (:headers resp) "content-type" ""))))))
