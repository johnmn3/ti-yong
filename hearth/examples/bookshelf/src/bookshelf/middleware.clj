(ns bookshelf.middleware
  "Custom application middleware for the BookShelf API.
   Demonstrates building domain-specific transformers on top of hearth."
  (:require
   [bookshelf.db :as db]
   [hearth.alpha.middleware :as mw]
   [ti-yong.alpha.transformer :as t]))

;; --- Authentication middleware ---

(def authenticate
  (-> t/transformer
      (assoc :doc "Middleware that checks for a valid session or API key.
   Sets :current-user on the env. Passes through if no auth present
   (use `require-auth` to enforce).

   Supports:
   - Session-based auth (cookie: hearth-session with :user-id in session)
   - API key auth (Authorization: Bearer bk-live-xxx)")
      (update :id conj ::authenticate)
      (update :tf conj
              ::authenticate
              (fn [env]
                (let [;; Try session-based auth first
                      user-id (get-in env [:session :user-id])
                      ;; Fall back to API key
                      auth-header (get-in env [:headers "authorization"])
                      api-key (when (and auth-header
                                        (clojure.string/starts-with? auth-header "Bearer "))
                                (subs auth-header 7))
                      api-key-data (when api-key (get @db/api-keys api-key))
                      ;; Resolve user
                      resolved-user-id (or user-id (:user-id api-key-data))
                      user (when resolved-user-id (get @db/users resolved-user-id))]
                  (cond-> env
                    user (assoc :current-user (dissoc user :password-hash)
                                :auth-method (if user-id :session :api-key)
                                :api-key-scope (:scope api-key-data))))))))

(def require-auth
  (-> t/transformer
      (assoc :doc "Middleware that rejects unauthenticated requests with 401.
   Must come after `authenticate` in the middleware chain.")
      (update :id conj ::require-auth)
      (update :tf conj
              ::require-auth
              (fn [env]
                (if (:current-user env)
                  env
                  (assoc env :env-op
                         (constantly {:status 401
                                      :headers {}
                                      :body {:error "Authentication required"}})))))))

(defn require-role
  "Middleware that restricts access to users with specific roles.
   Must come after `authenticate` in the middleware chain."
  [& roles]
  (let [allowed (set roles)]
    (-> t/transformer
        (assoc :doc (str "Requires role: " (mapv name roles)))
        (update :id conj ::require-role)
        (update :tf conj
                ::require-role
                (fn [env]
                  (let [user-role (get-in env [:current-user :role])]
                    (if (allowed user-role)
                      env
                      (assoc env :env-op
                             (constantly
                              {:status 403
                               :headers {}
                               :body {:error "Insufficient permissions"
                                      :required (mapv name roles)}})))))))))

;; --- Rate limiting middleware ---

(defonce ^:private rate-limit-store (atom {}))

(defn rate-limit
  "Middleware that limits requests per IP address.
   Options:
     :max-requests - max requests per window (default 100)
     :window-ms    - window size in ms (default 60000 = 1 min)"
  [& [{:keys [max-requests window-ms]
       :or {max-requests 100 window-ms 60000}}]]
  (-> t/transformer
      (assoc :doc (str "Rate limit: " max-requests " req/" (quot window-ms 1000) "s per IP"))
      (update :id conj ::rate-limit)
      (update :tf conj
              ::rate-limit
              (fn [env]
                (let [ip (or (:remote-addr env) "unknown")
                      now (System/currentTimeMillis)
                      key (str ip ":" (quot now window-ms))
                      current (get @rate-limit-store key 0)]
                  (swap! rate-limit-store assoc key (inc current))
                  (if (>= current max-requests)
                    (assoc env :env-op
                           (constantly
                            {:status 429
                             :headers {"Retry-After" (str (quot window-ms 1000))}
                             :body {:error "Too many requests"}}))
                    (assoc-in env [:response-headers "X-RateLimit-Remaining"]
                              (str (- max-requests current 1)))))))))

;; --- Request logging middleware ---

(defonce request-log (atom []))

(def request-logger
  (-> t/transformer
      (assoc :doc "Middleware that logs every request with timing info.
   Logs are stored in an atom for monitoring/analytics.")
      (update :id conj ::request-logger)
      (update :tf conj
              ::request-logger-start
              (fn [env]
                (assoc env ::request-start (System/nanoTime))))
      (update :tf-end conj
              ::request-logger-end
              (fn [env]
                (let [start (::request-start env)
                      duration-ms (when start
                                    (/ (- (System/nanoTime) start) 1e6))
                      log-entry {:method (:request-method env)
                                 :uri (:uri env)
                                 :status (get-in env [:res :status])
                                 :duration-ms duration-ms
                                 :remote-addr (:remote-addr env)
                                 :timestamp (str (java.time.Instant/now))}]
                  (swap! request-log conj log-entry)
                  env)))))

;; --- Content-Type validation middleware ---

(def require-json
  (-> t/transformer
      (assoc :doc "Middleware that rejects non-JSON request bodies with 415 Unsupported Media Type.")
      (update :id conj ::require-json)
      (update :tf conj
              ::require-json
              (fn [env]
                (let [method (:request-method env)
                      ct (get-in env [:headers "content-type"] "")]
                  (if (and (#{:post :put :patch} method)
                           (not (clojure.string/includes? ct "application/json")))
                    (assoc env :env-op
                           (constantly
                            {:status 415
                             :headers {}
                             :body {:error "Content-Type must be application/json"}}))
                    env))))))

;; --- Pagination middleware ---

(defn pagination-params
  "Middleware that extracts and validates pagination parameters from query string.
   Sets :pagination on env with {:page N :per-page N}."
  [& [{:keys [default-per-page max-per-page]
       :or {default-per-page 10 max-per-page 100}}]]
  (-> t/transformer
      (assoc :doc (str "Pagination: default " default-per-page " per page, max " max-per-page))
      (update :id conj ::pagination-params)
      (update :tf conj
              ::pagination-params
              (fn [env]
                (let [params (:query-params env)
                      page (try (Integer/parseInt (str (get params :page "1")))
                                (catch Exception _ 1))
                      per-page (try (Integer/parseInt (str (get params :per-page (str default-per-page))))
                                    (catch Exception _ default-per-page))
                      per-page (min per-page max-per-page)]
                  (assoc env :pagination {:page (max 1 page)
                                          :per-page per-page}))))))

;; --- Request ID middleware ---

(def request-id
  (-> t/transformer
      (assoc :doc "Middleware that assigns a unique ID to each request for tracing.")
      (update :id conj ::request-id)
      (update :tf conj
              ::request-id
              (fn [env]
                (let [id (or (get-in env [:headers "x-request-id"])
                             (str (java.util.UUID/randomUUID)))]
                  (assoc env :request-id id))))
      (update :tf-end conj
              ::request-id-header
              (fn [env]
                (let [res (:res env)]
                  (if (map? res)
                    (assoc env :res
                           (assoc-in res [:headers "X-Request-Id"] (:request-id env)))
                    env))))))

;; --- Cache control middleware ---

(defn cache-control
  "Middleware that sets Cache-Control header on responses."
  [directive]
  (-> t/transformer
      (assoc :doc (str "Cache-Control: " directive))
      (update :id conj ::cache-control)
      (update :tf-end conj
              ::cache-control
              (fn [env]
                (let [res (:res env)]
                  (if (and (map? res) (= 200 (:status res)))
                    (assoc env :res
                           (assoc-in res [:headers "Cache-Control"] directive))
                    env))))))

;; --- Load entity middleware (generic resource loader) ---

(defn load-entity
  "Middleware that loads an entity by :id path param from the given store.
   Assocs the entity onto env under the given key. Returns 404 if not found."
  [store entity-key entity-name]
  (-> t/transformer
      (assoc :doc (str "Load " entity-name " by :id path param"))
      (update :id conj ::load-entity)
      (update :tf conj
              ::load-entity
              (fn [env]
                (let [id-str (get-in env [:path-params-values "id"])
                      id (when id-str
                           (try (Integer/parseInt id-str)
                                (catch Exception _ nil)))
                      entity (when id (get @store id))]
                  (if entity
                    (assoc env entity-key entity)
                    (assoc env :env-op
                           (constantly
                            {:status 404
                             :headers {}
                             :body {:error (str entity-name " not found")
                                    :id id-str}}))))))))

;; --- CORS preflight middleware ---

(defn cors-preflight
  "Middleware that handles CORS preflight (OPTIONS) requests immediately."
  [opts]
  (-> t/transformer
      (assoc :doc "Handles CORS preflight (OPTIONS) requests immediately.")
      (update :id conj ::cors-preflight)
      (update :tf conj
              ::cors-preflight
              (fn [env]
                (if (= :options (:request-method env))
                  (assoc env :env-op
                         (constantly
                          {:status 204
                           :headers {"Access-Control-Allow-Origin" (:allowed-origins opts "*")
                                     "Access-Control-Allow-Methods" (:allowed-methods opts "GET, POST, PUT, DELETE, OPTIONS")
                                     "Access-Control-Allow-Headers" (:allowed-headers opts "Content-Type, Authorization, Accept")
                                     "Access-Control-Max-Age" (str (:max-age opts 86400))}
                           :body nil}))
                  env)))))
