(ns hearth.route-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [hearth.route :as route]
   [hearth.middleware :as mw]
   [ti-yong.alpha.transformer :as t]))

;; Phase 3: Route expansion + router transformer
;; Routes are defined as data (vectors), expanded into route-tfs,
;; and a router-tf matches requests to route-tfs.

(deftest route-expansion-test
  (testing "expand-routes converts route vectors into route maps"
    (let [handler-fn (fn [env] {:status 200 :body "hello"})
          routes (route/expand-routes
                  [["/api/items" :get handler-fn :route-name ::list-items]
                   ["/api/items/:id" :get handler-fn :route-name ::get-item]
                   ["/api/items" :post handler-fn :route-name ::create-item]])]
      (is (= 3 (count routes)))
      ;; Each route should have :path, :method, :handler, :route-name
      (is (every? #(and (:path %) (:method %) (:handler %) (:route-name %)) routes))
      (is (= "/" (:path (first (route/expand-routes [["/" :get handler-fn]])))))))

  (testing "expand-routes handles path parameters"
    (let [handler-fn (fn [env] {:status 200 :body "item"})
          routes (route/expand-routes
                  [["/items/:id" :get handler-fn :route-name ::get-item]])]
      (is (= "/items/:id" (:path (first routes))))
      (is (= [:id] (:path-params (first routes)))))))

(deftest route-matching-test
  (testing "match-route finds the correct route for method+path"
    (let [handler-fn (fn [env] {:status 200 :body "ok"})
          routes (route/expand-routes
                  [["/api/items" :get handler-fn :route-name ::list-items]
                   ["/api/items" :post handler-fn :route-name ::create-item]
                   ["/api/items/:id" :get handler-fn :route-name ::get-item]])
          match (route/match-route routes :get "/api/items")]
      (is (some? match))
      (is (= ::list-items (:route-name match)))))

  (testing "match-route returns nil for no match"
    (let [routes (route/expand-routes
                  [["/api/items" :get (fn [_] nil) :route-name ::list]])]
      (is (nil? (route/match-route routes :get "/api/nope")))))

  (testing "match-route extracts path params"
    (let [handler-fn (fn [env] {:status 200 :body "item"})
          routes (route/expand-routes
                  [["/api/items/:id" :get handler-fn :route-name ::get-item]])
          match (route/match-route routes :get "/api/items/42")]
      (is (some? match))
      (is (= ::get-item (:route-name match)))
      (is (= {"id" "42"} (:path-params-values match)))))

  (testing "match-route differentiates by method"
    (let [get-handler (fn [_] {:status 200})
          post-handler (fn [_] {:status 201})
          routes (route/expand-routes
                  [["/items" :get get-handler :route-name ::list]
                   ["/items" :post post-handler :route-name ::create]])
          get-match (route/match-route routes :get "/items")
          post-match (route/match-route routes :post "/items")]
      (is (= ::list (:route-name get-match)))
      (is (= ::create (:route-name post-match))))))

(deftest router-transformer-test
  (testing "router-tf is a transformer that routes requests"
    (let [list-handler (fn [env] {:status 200 :body "list"})
          get-handler (fn [env] {:status 200 :body "item-detail"})
          router (route/router
                  [["/items" :get list-handler :route-name ::list]
                   ["/items/:id" :get get-handler :route-name ::get-item]])]
      ;; Router should be a transformer
      (is (vector? (:id router)))
      (is (some #(= ::route/router %) (:id router)))))

  (testing "router-tf dispatches to correct handler via :env-op"
    (let [list-handler (fn [env] {:status 200 :body "list"})
          get-handler (fn [env] {:status 200 :body (str "item-" (get-in env [:path-params-values "id"]))})
          router (route/router
                  [["/items" :get list-handler :route-name ::list]
                   ["/items/:id" :get get-handler :route-name ::get-item]])]
      ;; Dispatch to list handler
      (let [result (-> router
                       (assoc :request-method :get :uri "/items")
                       (apply []))]
        (is (= {:status 200 :body "list"} result)))
      ;; Dispatch to get handler with path param
      (let [result (-> router
                       (assoc :request-method :get :uri "/items/42")
                       (apply []))]
        (is (= {:status 200 :body "item-42"} result)))))

  (testing "router-tf returns 404 for unmatched routes"
    (let [router (route/router
                  [["/items" :get (fn [_] {:status 200 :body "ok"}) :route-name ::list]])]
      (let [result (-> router
                       (assoc :request-method :get :uri "/nope")
                       (apply []))]
        (is (= 404 (:status result)))))))

(deftest route-with-middleware-test
  (testing "routes can specify middleware via :with in route definition"
    (let [observed (atom nil)
          handler (fn [env]
                    (reset! observed (:query-params env))
                    {:status 200 :body "ok"})
          router (route/router
                  [["/search" :get handler
                    :route-name ::search
                    :with [mw/query-params]]])]
      (-> router
          (assoc :request-method :get
                 :uri "/search"
                 :query-string "q=clojure")
          (apply []))
      (is (= {"q" "clojure"} @observed)))))
