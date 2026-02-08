(ns ti-yong.http.pet-store-test
  "Comprehensive Pet Store API tests via response-for (no running server).
   Ports patterns from Pedestal's pedestal-api example."
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [clojure.walk :as walk]
   [ti-yong.http :as http]
   [ti-yong.http.middleware :as mw]
   [examples.pet-store :as pet-store]))

;; --- Fixtures ---

(defn reset-store [f]
  (pet-store/reset-pets!)
  (f))

(use-fixtures :each reset-store)

;; --- Helpers ---

(def svc pet-store/service-map)

(defn- parse-json-body
  "Parse a JSON string body and keywordize keys for easier test assertions.
   Returns the response with :body replaced by parsed Clojure data."
  [resp]
  (if (and (map? resp) (string? (:body resp)))
    (try (update resp :body #(walk/keywordize-keys (mw/parse-json-string %)))
         (catch Exception _ resp))
    resp))

;; --- CRUD Tests ---

(deftest list-pets-test
  (testing "GET /pets returns all pets"
    (let [resp (parse-json-body (http/response-for svc :get "/pets"))]
      (is (= 200 (:status resp)))
      (is (= 3 (count (:pets (:body resp)))))
      (is (every? :name (:pets (:body resp)))))))

(deftest list-pets-sorted-test
  (testing "GET /pets?sort=asc returns pets sorted ascending"
    (let [resp (parse-json-body (http/response-for svc :get "/pets"
                                  {:query-string "sort=asc"}))
          names (mapv :name (:pets (:body resp)))]
      (is (= 200 (:status resp)))
      (is (= (sort names) names))))

  (testing "GET /pets?sort=desc returns pets sorted descending"
    (let [resp (parse-json-body (http/response-for svc :get "/pets"
                                  {:query-string "sort=desc"}))
          names (mapv :name (:pets (:body resp)))]
      (is (= 200 (:status resp)))
      (is (= (reverse (sort names)) names)))))

(deftest create-pet-test
  (testing "POST /pets creates a new pet"
    (let [resp (parse-json-body (http/response-for svc :post "/pets"
                                  {:body "{\"name\":\"Buddy\",\"type\":\"dog\",\"age\":2}"
                                   :headers {"content-type" "application/json"}}))]
      (is (= 201 (:status resp)))
      (is (= "Buddy" (:name (:body resp))))
      (is (integer? (:id (:body resp))))
      ;; Location header
      (is (re-find #"/pets/\d+" (get-in resp [:headers "Location"] "")))))

  (testing "new pet is persisted"
    (http/response-for svc :post "/pets"
                       {:body "{\"name\":\"Nemo\",\"type\":\"fish\",\"age\":1}"
                        :headers {"content-type" "application/json"}})
    (let [resp (parse-json-body (http/response-for svc :get "/pets"))]
      ;; 3 original + new = 4+
      (is (< 3 (count (:pets (:body resp))))))))

(deftest get-pet-test
  (testing "GET /pets/1 returns the specific pet"
    (let [resp (parse-json-body (http/response-for svc :get "/pets/1"))]
      (is (= 200 (:status resp)))
      (is (= "Rex" (:name (:body resp))))
      (is (= 1 (:id (:body resp))))))

  (testing "GET /pets/999 returns 404"
    (let [resp (http/response-for svc :get "/pets/999")]
      (is (= 404 (:status resp))))))

(deftest update-pet-test
  (testing "PUT /pets/1 updates the pet"
    (let [resp (http/response-for svc :put "/pets/1"
                                  {:body "{\"name\":\"Rex Jr\",\"age\":6}"
                                   :headers {"content-type" "application/json"}})]
      (is (= 200 (:status resp)))
      (is (re-find #"Updated" (:body resp))))
    ;; Verify the update
    (let [resp (parse-json-body (http/response-for svc :get "/pets/1"))]
      (is (= "Rex Jr" (:name (:body resp))))
      (is (= 6 (:age (:body resp))))))

  (testing "PUT /pets/999 returns 404"
    (let [resp (http/response-for svc :put "/pets/999"
                                  {:body "{\"name\":\"Ghost\"}"
                                   :headers {"content-type" "application/json"}})]
      (is (= 404 (:status resp))))))

(deftest delete-pet-test
  (testing "DELETE /pets/2 deletes the pet"
    (let [resp (http/response-for svc :delete "/pets/2")]
      (is (= 200 (:status resp)))
      (is (re-find #"Deleted Whiskers" (:body resp))))
    ;; Verify deletion
    (let [resp (http/response-for svc :get "/pets/2")]
      (is (= 404 (:status resp)))))

  (testing "DELETE /pets/999 returns 404"
    (let [resp (http/response-for svc :delete "/pets/999")]
      (is (= 404 (:status resp))))))

;; --- Search Tests ---

(deftest search-pets-test
  (testing "GET /pets/search?type=cat filters by type"
    (let [resp (parse-json-body (http/response-for svc :get "/pets/search"
                                  {:query-string "type=cat"}))]
      (is (= 200 (:status resp)))
      (is (= 1 (:count (:body resp))))))

  (testing "GET /pets/search?min-age=3 filters by min age"
    (let [resp (parse-json-body (http/response-for svc :get "/pets/search"
                                  {:query-string "min-age=3"}))]
      (is (= 200 (:status resp)))
      ;; Rex (5), Whiskers (3) — 2 pets >= 3
      (is (= 2 (:count (:body resp))))))

  (testing "GET /pets/search with no params returns all"
    (let [resp (parse-json-body (http/response-for svc :get "/pets/search"))]
      (is (= 200 (:status resp)))
      (is (= 3 (:count (:body resp)))))))

;; --- Home Page Test ---

(deftest home-page-test
  (testing "GET / returns HTML welcome page"
    (let [resp (http/response-for svc :get "/")]
      (is (= 200 (:status resp)))
      (is (re-find #"Pet Store" (:body resp))))))

;; --- Middleware Integration Tests ---

(deftest cors-headers-test
  (testing "CORS headers are present on responses"
    (let [resp (http/response-for svc :get "/pets")]
      ;; CORS middleware sets headers on response maps
      (is (= "*" (get-in resp [:headers "Access-Control-Allow-Origin"])))
      (is (re-find #"GET" (get-in resp [:headers "Access-Control-Allow-Methods"] ""))))))

(deftest error-handling-test
  (testing "runtime errors are caught and return 500"
    ;; Create a service with a handler that throws
    (let [bad-svc {::http/routes [["/boom" :get (fn [_] (throw (ex-info "kaboom" {})))
                                   :route-name ::boom]]
                   ::http/with [(http/error-middleware)]}
          resp (http/response-for bad-svc :get "/boom")]
      (is (= 500 (:status resp)))
      (is (re-find #"kaboom" (:body resp))))))

(deftest four-oh-four-test
  (testing "unmatched routes return 404"
    (let [resp (http/response-for svc :get "/nonexistent")]
      (is (= 404 (:status resp))))))

;; --- New Middleware Tests ---

(deftest body-params-test
  (testing "body-params parses JSON into :body-params"
    (let [observed (atom nil)
          test-svc {::http/routes [["/test" :post
                                    (fn [env]
                                      (reset! observed (:body-params env))
                                      {:status 200 :headers {} :body "ok"})
                                    :route-name ::bp-test
                                    :with [mw/body-params]]]}]
      (http/response-for test-svc :post "/test"
                         {:body "{\"key\":\"value\"}"
                          :headers {"content-type" "application/json"}})
      (is (= {"key" "value"} @observed))))

  (testing "body-params parses form-encoded into :body-params"
    (let [observed (atom nil)
          test-svc {::http/routes [["/test" :post
                                    (fn [env]
                                      (reset! observed (:body-params env))
                                      {:status 200 :headers {} :body "ok"})
                                    :route-name ::fp-test
                                    :with [mw/body-params]]]}]
      (http/response-for test-svc :post "/test"
                         {:body "name=alice&age=30"
                          :headers {"content-type" "application/x-www-form-urlencoded"}})
      (is (= {"name" "alice" "age" "30"} @observed)))))

(deftest keyword-params-test
  (testing "keyword-params keywordizes query-params and body-params"
    (let [observed (atom nil)
          test-svc {::http/routes [["/test" :post
                                    (fn [env]
                                      (reset! observed {:qp (:query-params env)
                                                        :bp (:body-params env)})
                                      {:status 200 :headers {} :body "ok"})
                                    :route-name ::kp-test
                                    ;; natural order: parse first, then keywordize
                                    :with [mw/query-params mw/body-params mw/keyword-params]]]}]
      (http/response-for test-svc :post "/test"
                         {:query-string "page=1"
                          :body "{\"name\":\"test\"}"
                          :headers {"content-type" "application/json"}})
      (is (= {:page "1"} (:qp @observed)))
      (is (= {:name "test"} (:bp @observed))))))

(deftest content-negotiation-test
  (testing "content-negotiation serializes to JSON for application/json Accept"
    (let [test-svc {::http/routes [["/data" :get
                                    (fn [_] {:status 200 :headers {} :body {:key "value"}})
                                    :route-name ::cn-test]]
                    ::http/with [(mw/content-negotiation)]}]
      (let [resp (http/response-for test-svc :get "/data"
                                    {:headers {"accept" "application/json"}})]
        (is (= 200 (:status resp)))
        (is (string? (:body resp)))
        (is (re-find #"\"key\"" (:body resp)))
        (is (= "application/json" (get-in resp [:headers "Content-Type"]))))))

  (testing "content-negotiation serializes to EDN for application/edn Accept"
    (let [test-svc {::http/routes [["/data" :get
                                    (fn [_] {:status 200 :headers {} :body {:key "value"}})
                                    :route-name ::edn-test]]
                    ::http/with [(mw/content-negotiation)]}]
      (let [resp (http/response-for test-svc :get "/data"
                                    {:headers {"accept" "application/edn"}})]
        (is (= 200 (:status resp)))
        (is (= "application/edn" (get-in resp [:headers "Content-Type"])))))))
