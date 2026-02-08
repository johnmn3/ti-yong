(ns hearth.alpha.integration-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [hearth.alpha :as http]
   [hearth.alpha.response :as resp]
   [hearth.alpha.request :as req]
   [hearth.alpha.middleware :as mw]
   [hearth.alpha.error :as err]
   [ti-yong.alpha.transformer :as t]))

;; Integration tests: full-stack scenarios exercising the complete pipeline.

;; --- Hello World Service ---

(def hello-service-map
  {::http/routes [["/hello" :get (fn [_] {:status 200 :headers {} :body "Hello, World!"})
                   :route-name ::hello]
                  ["/hello/:name" :get
                   (fn [env]
                     {:status 200
                      :headers {}
                      :body (str "Hello, " (get-in env [:path-params-values "name"]) "!")})
                   :route-name ::hello-name]]})

(deftest hello-world-integration-test
  (testing "GET /hello returns Hello, World!"
    (let [resp (http/response-for hello-service-map :get "/hello")]
      (is (= 200 (:status resp)))
      (is (= "Hello, World!" (:body resp)))))

  (testing "GET /hello/Alice returns personalized greeting"
    (let [resp (http/response-for hello-service-map :get "/hello/Alice")]
      (is (= 200 (:status resp)))
      (is (= "Hello, Alice!" (:body resp)))))

  (testing "GET /nonexistent returns 404"
    (is (= 404 (:status (http/response-for hello-service-map :get "/nonexistent"))))))

;; --- Todo API Service ---

(def todos-store (atom {"1" {:id "1" :title "Buy milk" :done false}
                        "2" {:id "2" :title "Write tests" :done true}}))

(defn list-todos [_env]
  {:status 200
   :headers {}
   :body (vec (vals @todos-store))})

(defn get-todo [env]
  (let [id (get-in env [:path-params-values "id"])]
    (if-let [todo (get @todos-store id)]
      {:status 200 :headers {} :body todo}
      {:status 404 :headers {} :body (str "Todo " id " not found")})))

(defn create-todo [env]
  (let [body (:json-body env)
        id (str (inc (count @todos-store)))
        todo (assoc body "id" id "done" false)]
    (swap! todos-store assoc id todo)
    {:status 201 :headers {"Location" (str "/todos/" id)} :body todo}))

(def todo-service-map
  {::http/routes [["/todos" :get list-todos :route-name ::list-todos]
                  ["/todos/:id" :get get-todo :route-name ::get-todo]
                  ["/todos" :post create-todo
                   :route-name ::create-todo
                   :with [mw/json-body]]]
   ::http/with [err/error-handler]})

(deftest todo-api-integration-test
  ;; Reset store for each test run
  (reset! todos-store {"1" {:id "1" :title "Buy milk" :done false}
                       "2" {:id "2" :title "Write tests" :done true}})

  (testing "GET /todos returns all todos"
    (let [resp (http/response-for todo-service-map :get "/todos")]
      (is (= 200 (:status resp)))
      (is (= 2 (count (:body resp))))))

  (testing "GET /todos/1 returns specific todo"
    (let [resp (http/response-for todo-service-map :get "/todos/1")]
      (is (= 200 (:status resp)))
      (is (= "Buy milk" (:title (:body resp))))))

  (testing "GET /todos/999 returns 404"
    (let [resp (http/response-for todo-service-map :get "/todos/999")]
      (is (= 404 (:status resp)))))

  (testing "POST /todos creates a new todo"
    (let [resp (http/response-for todo-service-map :post "/todos"
                                  {:body "{\"title\":\"Learn Clojure\"}"
                                   :headers {"content-type" "application/json"}})]
      (is (= 201 (:status resp)))
      (is (= "Learn Clojure" (get (:body resp) "title")))
      ;; Verify it was added to the store
      (is (= 3 (count @todos-store))))))

;; --- Content Negotiation Service ---

(deftest content-negotiation-integration-test
  (testing "JSON response middleware serializes map responses"
    (let [service-map {::http/routes [["/data" :get
                                       (fn [_] {:name "test" :value 42})
                                       :route-name ::data]]
                       ::http/with [mw/json-response]}]
      (let [resp (http/response-for service-map :get "/data")]
        (is (string? resp))
        (is (re-find #"\"name\"" resp))
        (is (re-find #"\"test\"" resp))))))

;; --- Service with full middleware stack ---

(deftest full-middleware-stack-test
  (testing "service with query-params + json-body + error-handler + content-type"
    (let [log (atom [])
          service-map {::http/routes [["/search" :get
                                       (fn [env]
                                         (let [q (get (:query-params env) "q" "")]
                                           {:status 200
                                            :headers {}
                                            :body (str "Results for: " q)}))
                                       :route-name ::search
                                       :with [mw/query-params]]
                                      ["/echo" :post
                                       (fn [env]
                                         {:status 200
                                          :headers {}
                                          :body (:json-body env)})
                                       :route-name ::echo
                                       :with [mw/json-body]]
                                      ["/error" :get
                                       (fn [_] (throw (ex-info "intentional" {:code 42})))
                                       :route-name ::error]]
                       ::http/with [err/error-handler
                                    (mw/logging log)
                                    (mw/default-content-type "text/plain")]}]

      ;; Search with query params
      (let [resp (http/response-for service-map :get "/search"
                                    {:query-string "q=clojure"})]
        (is (= 200 (:status resp)))
        (is (= "Results for: clojure" (:body resp))))

      ;; Echo JSON body
      (let [resp (http/response-for service-map :post "/echo"
                                    {:body "{\"msg\":\"hi\"}"
                                     :headers {"content-type" "application/json"}})]
        (is (= 200 (:status resp)))
        (is (= {"msg" "hi"} (:body resp))))

      ;; Error handling
      (let [resp (http/response-for service-map :get "/error")]
        (is (= 500 (:status resp)))
        (is (re-find #"intentional" (:body resp))))

      ;; Logging captured all 3 requests
      (is (= 3 (count @log))))))

;; --- Response transformer integration ---

(deftest response-transformer-integration-test
  (testing "response helpers work as route handlers"
    (let [service-map {::http/routes [["/ok" :get (fn [_] (resp/to-ring (resp/ok "fine")))
                                       :route-name ::ok]
                                      ["/created" :post
                                       (fn [_] (resp/to-ring (resp/created "/items/1" {:id 1})))
                                       :route-name ::created]
                                      ["/gone" :get
                                       (fn [_] (resp/to-ring (resp/not-found "gone")))
                                       :route-name ::gone]]}]
      (let [resp (http/response-for service-map :get "/ok")]
        (is (= 200 (:status resp)))
        (is (= "fine" (:body resp))))
      (let [resp (http/response-for service-map :post "/created")]
        (is (= 201 (:status resp)))
        (is (= "/items/1" (get-in resp [:headers "Location"]))))
      (let [resp (http/response-for service-map :get "/gone")]
        (is (= 404 (:status resp)))))))

;; --- Request transformer integration ---

(deftest request-transformer-integration-test
  (testing "mock-request can be used for testing routes directly"
    (let [r (req/mock-request :get "/api/test" {:query-string "page=2"})]
      (is (= :get (req/method r)))
      (is (= "/api/test" (req/path r)))
      (is (= "page=2" (:query-string r))))))
