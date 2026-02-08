# Implementation Plan: Pedestal Port to ti-yong Transformers

## Vision

Replace Pedestal's interceptor-chain architecture with ti-yong transformers, demonstrating that **functions-as-data** subsumes and improves upon the interceptor model. In this port:

- **Endpoint handlers** are transformers (callable maps with pipelines)
- **Request and response** are also transformers (maps with specs, defaults, and behavior)
- **Interceptors are eliminated entirely** -- their concerns become `:tf-pre`, `:tf`, `:out`, and `:tf-end` pipeline stages composed via `:with` mixins
- **The route table** composes transformers hierarchically via inheritance

The result: a web framework where every artifact -- the server, the route, the handler, the request, the response -- is a composable, inspectable, spec-validated data structure that is also callable.

---

## Architecture Overview

```
HTTP Request (from Ring/Jetty/Http-Kit)
    |
    v
request-tf  <-- a transformer wrapping the Ring request map
    |              has specs for :uri, :request-method, etc.
    |              has :tf-pre stages for parsing (query-params, body, etc.)
    |              callable: (request-tf :uri) => "/users/123"
    |
    v
route-tf    <-- matched from route table, inherits common-tf via :with
    |              carries :op (the handler logic)
    |              carries :tf-pre stages (auth, logging, etc.) via mixins
    |              carries :specs for required request keys
    |
    v
(route-tf request-tf)  <-- invoke: runs full pipeline
    |
    v
response-tf <-- returned by the handler
    |              has specs for :status, :headers, :body
    |              has :out stages for serialization (JSON, EDN, etc.)
    |              has :tf-end stages for headers (CORS, security, etc.)
    |              callable: (response-tf :status) => 200
    |
    v
Ring Response (written to HTTP adapter)
```

---

## Project Structure

```
ti-yong-http/
  deps.edn
  src/
    ti_yong/
      http.clj                    ;; Public API: create-server, start, stop
      http/
        request.clj               ;; request-tf: transformer wrapping Ring request
        response.clj              ;; response-tf: transformer for responses + helpers
        route.clj                 ;; Route table definition + router-tf
        service.clj               ;; Service transformer: the root pipeline
        middleware.clj             ;; Standard middleware as transformer mixins
        error.clj                 ;; Error handling
        adapter/
          ring.clj                ;; Ring adapter (works with any Ring server)
  test/
    ti_yong/
      http_test.clj               ;; Integration tests
      http/
        request_test.clj
        response_test.clj
        route_test.clj
        middleware_test.clj
  examples/
    hello_world.clj               ;; Minimal example
    todo_api.clj                  ;; Full CRUD API (Pedestal's "Your First API" ported)
    content_negotiation.clj       ;; Content-type negotiation example
```

---

## Phase 1: Core Primitives (~300 lines)

### 1.1 Request Transformer (`ti-yong.http.request`)

The Ring request map becomes a transformer. Instead of a plain map flowing through interceptors, the request itself has behavior.

```clojure
(ns ti-yong.http.request
  (:require
   [clojure.spec.alpha :as s]
   [ti-yong.alpha.transformer :as t]
   [ti-yong.alpha.root :as r]))

;; --- Specs for a valid Ring request ---
(s/def ::uri string?)
(s/def ::request-method #{:get :post :put :delete :patch :head :options})
(s/def ::headers (s/map-of string? string?))
(s/def ::server-port pos-int?)
(s/def ::server-name string?)
(s/def ::scheme #{:http :https})

(s/def ::ring-request
  (s/keys :req-un [::uri ::request-method ::headers]))

;; --- The request transformer ---
;; When called with no args, returns itself (the full request data).
;; When called with a key, acts as lookup: (req :uri) => "/users/123"
;; The :tf-pre pipeline parses query params, body, etc.

(def request-tf
  "Base request transformer. Wrap a Ring request map with this
   to get spec validation, query-param parsing, and composable
   request processing."
  (-> t/transformer
      (update :id conj ::request)
      (update :specs conj ::request ::ring-request)
      (assoc :env-op (fn [env] env))))  ;; default: return self

(defn wrap-request
  "Wraps a Ring request map into a request transformer."
  [ring-request]
  (merge request-tf ring-request))
```

**What this gives you that Pedestal doesn't:**
- `(req :uri)` -- the request is callable, acts as a lookup
- `(req :body)` -- same
- Specs are validated at invocation time -- if a route requires `:user-id` in path-params, the spec catches it
- Request "middleware" (parsing, auth) are `:tf-pre` stages on the request itself, not separate interceptors

### 1.2 Response Transformer (`ti-yong.http.response`)

```clojure
(ns ti-yong.http.response
  (:require
   [clojure.spec.alpha :as s]
   [ti-yong.alpha.transformer :as t]))

;; --- Specs ---
(s/def ::status (s/and pos-int? #(<= 100 % 599)))
(s/def ::body any?)
(s/def ::headers (s/map-of string? string?))

(s/def ::ring-response
  (s/keys :req-un [::status]
          :opt-un [::body ::headers]))

;; --- Response transformer ---
(def response-tf
  "Base response transformer. Responses carry their own serialization
   pipeline (:out stages) and header-setting logic (:tf-end stages)."
  (-> t/transformer
      (update :id conj ::response)
      (update :specs conj ::response ::ring-response)
      (assoc :headers {})
      (assoc :env-op (fn [{:keys [status body headers]}]
                       {:status status :body body :headers (or headers {})}))))

;; --- Response constructors ---
;; Each returns a response transformer, not a plain map.

(defn ok [body]
  (merge response-tf {:status 200 :body body}))

(defn created [body & {:as extra-headers}]
  (merge response-tf {:status 201 :body body :headers (or extra-headers {})}))

(defn not-found [body]
  (merge response-tf {:status 404 :body body}))

(defn bad-request [body]
  (merge response-tf {:status 400 :body body}))

(defn redirect [url]
  (merge response-tf {:status 302 :body "" :headers {"Location" url}}))
```

**What this gives you:**
- Response is inspectable: `(:status resp)`, `(:headers resp)`
- Response carries its own serialization: add `:out` stages to transform `:body` (e.g., JSON encoding)
- Spec-validated: can't accidentally return `{:stauts 200}` (typo) -- spec catches it
- Composable: `(-> (ok body) (update :headers assoc "X-Custom" "value"))`

### 1.3 Handler / Endpoint Transformer

A handler is simply a transformer whose `:op` (or `:env-op`) does the application logic. No special type needed -- it's just a transformer with specific keys:

```clojure
;; A handler is a transformer that takes a request and returns a response.
;; The :op receives the request (as args), the pipeline does the rest.

(def hello-handler
  (-> t/transformer
      (update :id conj ::hello)
      (assoc :op (fn [request]
                   (ok (str "Hello, " (get-in request [:query-params :name] "world") "!"))))))

;; Call it directly in tests:
;; (hello-handler {:uri "/hello" :request-method :get :headers {} :query-params {:name "Bob"}})
;; => {:status 200 :body "Hello, Bob!" :headers {}}
```

### 1.4 Middleware as Mixins (`ti-yong.http.middleware`)

Every Pedestal interceptor becomes a mixin transformer. Instead of a map with `:enter`/`:leave`/`:error`, it's a transformer with `:tf-pre` (enter), `:out`/`:tf-end` (leave), and a future `:tf-err` (error).

```clojure
(ns ti-yong.http.middleware
  (:require
   [clojure.spec.alpha :as s]
   [ti-yong.alpha.transformer :as t]
   [ti-yong.alpha.root :as r]))

;; --- Query Params ---
(def query-params-mixin
  "Parses :query-string into :query-params on the request."
  (-> t/transformer
      (update :id conj ::query-params)
      (update :in conj
              ::query-params
              (fn [args]
                (mapv (fn [req]
                        (if (:query-string req)
                          (assoc req :query-params (parse-query-string (:query-string req)))
                          req))
                      args)))))

;; --- JSON Body ---
(def json-body-mixin
  "Parses JSON request body on enter, serializes response body on leave."
  (-> t/transformer
      (update :id conj ::json-body)
      ;; Enter: parse JSON body from request
      (update :in conj
              ::parse-json-body
              (fn [args]
                (mapv (fn [req]
                        (if (and (:body req)
                                 (= "application/json"
                                    (get-in req [:headers "content-type"])))
                          (assoc req :json-params (parse-json (:body req)))
                          req))
                      args)))
      ;; Leave: serialize response body to JSON
      (update :out conj
              ::serialize-json
              (fn [response]
                (if (and (map? response) (not (string? (:body response))))
                  (-> response
                      (update :body to-json)
                      (assoc-in [:headers "Content-Type"] "application/json"))
                  response)))))

;; --- Logging ---
(def logging-mixin
  "Logs request method + URI on enter, status on leave."
  (-> t/transformer
      (update :id conj ::logging)
      (update :tf-pre conj
              ::log-request
              (fn [env]
                (let [req (first (:args env))]
                  (println (:request-method req) (:uri req)))
                env))
      (update :tf-end conj
              ::log-response
              (fn [env]
                (println "=>" (:status (:res env)))
                env))))

;; --- Auth ---
(defn auth-mixin
  "Validates auth token. Short-circuits with 401 if invalid.
   Takes an auth-fn that receives the request and returns identity or nil."
  [auth-fn]
  (-> t/transformer
      (update :id conj ::auth)
      (update :in conj
              ::authenticate
              (fn [args]
                (mapv (fn [req]
                        (if-let [identity (auth-fn req)]
                          (assoc req :identity identity)
                          (throw (ex-info "Unauthorized" {:status 401}))))
                      args)))))

;; --- CORS ---
(defn cors-mixin
  "Adds CORS headers to responses."
  [allowed-origins]
  (-> t/transformer
      (update :id conj ::cors)
      (update :out conj
              ::add-cors-headers
              (fn [response]
                (-> response
                    (assoc-in [:headers "Access-Control-Allow-Origin"]
                              (if (fn? allowed-origins) "*" (first allowed-origins)))
                    (assoc-in [:headers "Access-Control-Allow-Methods"]
                              "GET, POST, PUT, DELETE, OPTIONS")
                    (assoc-in [:headers "Access-Control-Allow-Headers"]
                              "Content-Type, Authorization"))))))

;; --- Content Negotiation ---
(def content-negotiation-mixin
  (-> t/transformer
      (update :id conj ::content-negotiation)
      (update :in conj
              ::negotiate
              (fn [args]
                (mapv (fn [req]
                        (let [accept (get-in req [:headers "accept"] "text/plain")]
                          (assoc req :accept accept)))
                      args)))))

;; --- Secure Headers ---
(def secure-headers-mixin
  (-> t/transformer
      (update :id conj ::secure-headers)
      (update :out conj
              ::add-secure-headers
              (fn [response]
                (-> response
                    (assoc-in [:headers "X-Frame-Options"] "DENY")
                    (assoc-in [:headers "X-Content-Type-Options"] "nosniff")
                    (assoc-in [:headers "X-XSS-Protection"] "1; mode=block")
                    (assoc-in [:headers "Strict-Transport-Security"]
                              "max-age=31536000; includeSubDomains"))))))

;; --- Not Found ---
(def not-found-mixin
  "Sets 404 response if :op returns nil."
  (-> t/transformer
      (update :id conj ::not-found)
      (update :out conj
              ::maybe-not-found
              (fn [response]
                (if (nil? response)
                  {:status 404 :body "Not Found" :headers {}}
                  response)))))

;; --- Session ---
(defn session-mixin
  "Loads session from store on enter, saves on leave."
  [session-store]
  (-> t/transformer
      (update :id conj ::session)
      (update :in conj
              ::load-session
              (fn [args]
                (mapv (fn [req]
                        (let [session-id (get-in req [:cookies "session-id" :value])
                              session    (when session-id (load-session session-store session-id))]
                          (assoc req :session (or session {}))))
                      args)))
      (update :tf-end conj
              ::save-session
              (fn [env]
                (when-let [session (:session (first (:args env)))]
                  (save-session session-store session))
                env))))
```

**Key insight**: Each mixin is a transformer itself. You can:
- Call it standalone for testing: `(json-body-mixin some-request)`
- Inspect its pipeline: `(:in json-body-mixin)`, `(:out json-body-mixin)`
- Compose them: `(update handler :with conj json-body-mixin auth-mixin)`
- Override any stage in a child by using the same keyword ID

---

## Phase 2: Routing (~150 lines)

### 2.1 Route Definition (`ti-yong.http.route`)

Routes are defined as a set of vectors (Pedestal table syntax), but each route's interceptor chain is replaced by `:with` mixins on the handler transformer.

```clojure
(ns ti-yong.http.route
  (:require
   [ti-yong.alpha.transformer :as t]
   [ti-yong.http.middleware :as mw]))

;; --- Route table syntax ---
;; #{["/path" :method handler-tf :route-name ::name]}
;; #{["/path" :method [mixin-a mixin-b handler-tf]]}
;;
;; When a vector of transformers is given, the last is the handler
;; and the rest become :with mixins on it automatically.

(defn expand-route
  "Expands a single route vector into a route map."
  [[path method handler-or-chain & {:as opts}]]
  (let [[mixins handler] (if (vector? handler-or-chain)
                           [(butlast handler-or-chain) (last handler-or-chain)]
                           [[] handler-or-chain])
        ;; If handler is a plain fn, wrap it in a transformer
        handler-tf (if (fn? handler)
                     (-> t/transformer
                         (update :id conj (or (:route-name opts)
                                              (keyword (gensym "handler-"))))
                         (assoc :op handler))
                     handler)
        ;; Compose mixins via :with
        composed (if (seq mixins)
                   (update handler-tf :with into mixins)
                   handler-tf)
        route-name (or (:route-name opts)
                       (last (:id composed)))]
    {:path       path
     :method     method
     :handler    composed
     :route-name route-name
     :path-parts (parse-path path)
     :path-re    (path->regex path)
     :constraints (:constraints opts)}))

(defn expand-routes
  "Expands a set of route vectors into a routing table."
  [route-set]
  (->> route-set
       (map expand-route)
       (into [])))

;; --- Router ---
;; The router is itself a transformer. Its :op matches the request
;; to a route and returns the composed handler transformer.

(defn match-route
  "Matches a request against the routing table.
   Returns [route path-params] or nil."
  [routes {:keys [uri request-method]}]
  (->> routes
       (filter (fn [{:keys [method path-re constraints]}]
                 (and (or (= method :any) (= method request-method))
                      (re-matches path-re uri))))
       (first)
       ((fn [route]
          (when route
            (let [path-params (extract-path-params (:path-parts route) uri)]
              [route path-params]))))))

(defn router
  "Creates a router transformer from a routing table.
   When invoked, matches the request and delegates to the matched handler."
  [routes]
  (-> t/transformer
      (update :id conj ::router)
      (assoc :routes routes)
      (assoc :env-op
             (fn [{:keys [routes args]}]
               (let [request (first args)]
                 (if-let [[route path-params] (match-route routes request)]
                   (let [request' (assoc request :path-params path-params
                                                 :route route)]
                     ;; Invoke the matched handler transformer with the request
                     ((:handler route) request'))
                   {:status 404 :body "Not Found" :headers {}}))))))
```

**Comparison to Pedestal:**

| Pedestal | ti-yong |
|----------|---------|
| `(interceptor.chain/enqueue ctx interceptors)` | `(update handler :with into mixins)` -- static at route-definition time |
| Route-specific interceptors queued at runtime | Route-specific middleware composed at definition time via `:with` |
| Router modifies context in-place | Router invokes the matched handler transformer |
| Manual `::http/interceptors` ordering | Automatic deduplication via pipeline keyword IDs |

### 2.2 How Route Composition Works

```clojure
;; --- Common middleware (replaces Pedestal's common-interceptors pattern) ---
(def api-defaults
  "Standard API middleware stack. Compose via :with."
  (-> t/transformer
      (update :id conj ::api-defaults)
      (update :with conj
              mw/query-params-mixin
              mw/json-body-mixin
              mw/logging-mixin
              mw/not-found-mixin
              mw/secure-headers-mixin)))

(def authed-api
  "API defaults + authentication."
  (-> api-defaults
      (update :id conj ::authed-api)
      (update :with conj (mw/auth-mixin my-auth-fn))))

;; --- Route handlers inherit from these ---
(def list-users
  (-> authed-api
      (update :id conj ::list-users)
      (assoc :op (fn [req] (ok (db/list-users (:db req)))))))

(def view-user
  (-> authed-api
      (update :id conj ::view-user)
      (assoc :op (fn [req]
                   (if-let [user (db/get-user (:db req) (get-in req [:path-params :id]))]
                     (ok user)
                     (not-found "User not found"))))))

(def create-user
  (-> authed-api
      (update :id conj ::create-user)
      ;; Add a spec: request MUST have :json-params with :name
      (update :specs conj ::create-user-spec ::create-user-request-spec)
      (assoc :op (fn [req]
                   (let [user (db/create-user (:json-params req))]
                     (created user "Location" (str "/users/" (:id user))))))))

;; --- Route table ---
(def routes
  (expand-routes
    #{["/api/users"     :get    list-users   :route-name ::list-users]
      ["/api/users/:id" :get    view-user    :route-name ::view-user]
      ["/api/users"     :post   create-user  :route-name ::create-user]}))
```

---

## Phase 3: Service & Server (~150 lines)

### 3.1 Service Transformer (`ti-yong.http.service`)

The service is the root transformer that wraps request handling.

```clojure
(ns ti-yong.http.service
  (:require
   [ti-yong.alpha.transformer :as t]
   [ti-yong.http.route :as route]
   [ti-yong.http.request :as req]
   [ti-yong.http.response :as resp]
   [ti-yong.http.middleware :as mw]))

(defn service
  "Creates a service transformer from a config map.
   The service is callable: (svc ring-request) => ring-response."
  [{:keys [routes router-type]
    :or   {router-type :linear}}]
  (let [routing-table (route/expand-routes routes)
        router-tf     (route/router routing-table)]
    (-> t/transformer
        (update :id conj ::service)
        (assoc :router router-tf)
        (assoc :env-op
               (fn [{:keys [router args]}]
                 (let [ring-request (first args)
                       ;; Wrap the raw Ring request as a request transformer
                       request (req/wrap-request ring-request)
                       ;; Route and invoke
                       result  (router request)]
                   ;; If result is a response transformer, extract Ring response
                   ;; Otherwise, assume it's already a Ring response map
                   (if (map? result)
                     (select-keys result [:status :body :headers])
                     {:status 500 :body "Internal Server Error" :headers {}})))))))
```

### 3.2 Ring Adapter (`ti-yong.http.adapter.ring`)

```clojure
(ns ti-yong.http.adapter.ring
  (:require
   [ring.adapter.jetty :as jetty]
   [ti-yong.http.service :as svc]))

(defn create-server
  "Creates a Ring-compatible server from a service config map.
   Returns a map with :server, :start-fn, :stop-fn."
  [{:keys [port host join?]
    :or   {port 8080 host "0.0.0.0" join? false}
    :as   config}]
  (let [service-tf (svc/service config)
        ;; The handler fn: Ring request -> Ring response
        handler    (fn [ring-request]
                     (service-tf ring-request))
        server     (jetty/run-jetty handler
                     {:port  port
                      :host  host
                      :join? join?})]
    {:server   server
     :service  service-tf
     :stop-fn  #(.stop server)}))

(defn start [server-map]
  (.start (:server server-map))
  server-map)

(defn stop [server-map]
  ((:stop-fn server-map))
  server-map)
```

### 3.3 Public API (`ti-yong.http`)

```clojure
(ns ti-yong.http
  (:require
   [ti-yong.http.adapter.ring :as ring-adapter]
   [ti-yong.http.service :as svc]
   [ti-yong.http.route :as route]
   [ti-yong.http.response :as resp]
   [ti-yong.http.middleware :as mw]))

(defn create-server [config]
  (ring-adapter/create-server config))

(defn start [server]
  (ring-adapter/start server))

(defn stop [server]
  (ring-adapter/stop server))

;; --- Testing (replaces Pedestal's response-for) ---
(defn response-for
  "Test helper. Calls the service directly without starting a server.
   Returns a Ring response map."
  [service-tf method uri & {:keys [headers body]}]
  (service-tf {:uri            uri
               :request-method method
               :headers        (or headers {})
               :body           body
               :scheme         :http
               :server-name    "localhost"
               :server-port    80}))
```

---

## Phase 4: Error Handling (~100 lines)

### 4.1 Error Handler Mixin (`ti-yong.http.error`)

Since ti-yong doesn't currently have a built-in error stage, we add error handling as a `:tf-pre` wrapper that catches exceptions from the `:op`:

```clojure
(ns ti-yong.http.error
  (:require [ti-yong.alpha.transformer :as t]))

(defn error-handler-mixin
  "Wraps the handler's :op in a try/catch.
   error-fn receives [request exception] and returns a response map."
  [error-fn]
  (-> t/transformer
      (update :id conj ::error-handler)
      (update :tf conj
              ::wrap-error
              (fn [env]
                (let [original-op (or (:op env) identity)]
                  (assoc env :op
                         (fn [& args]
                           (try
                             (apply original-op args)
                             (catch Exception e
                               (error-fn (first args) e))))))))))

(def default-error-handler
  "Catches all exceptions and returns appropriate HTTP responses."
  (error-handler-mixin
    (fn [request ex]
      (let [data (ex-data ex)]
        (cond
          ;; Known HTTP status (e.g., auth failures throw {:status 401})
          (:status data)
          {:status  (:status data)
           :body    (or (:body data) (ex-message ex))
           :headers (or (:headers data) {})}

          ;; Spec validation failure
          (:error data)
          {:status 400 :body (str "Validation error: " (ex-message ex)) :headers {}}

          ;; Unknown error
          :else
          {:status 500 :body "Internal Server Error" :headers {}})))))
```

---

## Phase 5: Example Applications

### 5.1 Hello World (`examples/hello_world.clj`)

```clojure
(ns examples.hello-world
  (:require
   [ti-yong.http :as http]
   [ti-yong.http.response :refer [ok]]
   [ti-yong.alpha.transformer :as t]))

(defn greet [request]
  (ok (str "Hello, " (get-in request [:query-params :name] "world") "!")))

(def routes
  #{["/greet" :get greet :route-name ::greet]})

(defn -main [& _args]
  (-> {:routes routes :port 8080}
      http/create-server
      http/start)
  (println "Server running on http://localhost:8080"))
```

### 5.2 Todo API (`examples/todo_api.clj`)

Port of Pedestal's canonical "Your First API" tutorial:

```clojure
(ns examples.todo-api
  (:require
   [ti-yong.http :as http]
   [ti-yong.http.response :as resp]
   [ti-yong.http.middleware :as mw]
   [ti-yong.http.error :as err]
   [ti-yong.alpha.transformer :as t]
   [clojure.spec.alpha :as s]))

;; --- Database ---
(defonce database (atom {}))

(defn make-list [name] {:name name :items {}})
(defn make-item [name] {:name name :done? false})

;; --- Specs for request validation ---
(s/def ::list-id string?)
(s/def ::has-list-id (s/keys :req-un [::path-params]))

;; --- Database mixin ---
;; Replaces Pedestal's db-interceptor.
;; Enter: inject db snapshot into request
;; Leave: apply transaction if present
(def db-mixin
  (-> t/transformer
      (update :id conj ::db)
      (update :in conj
              ::inject-db
              (fn [args]
                (mapv #(assoc % :database @database) args)))))

;; --- Entity render mixin ---
(def entity-render-mixin
  (-> t/transformer
      (update :id conj ::entity-render)
      (update :out conj
              ::render-entity
              (fn [result]
                (if (and (map? result) (not (:status result)))
                  (resp/ok result)
                  result)))))

;; --- Handlers ---
(def list-create
  (-> t/transformer
      (update :id conj ::list-create)
      (update :with conj db-mixin err/default-error-handler)
      (assoc :op (fn [req]
                   (let [nm     (get-in req [:query-params :name] "Unnamed List")
                         new-list (make-list nm)
                         db-id    (str (gensym "l"))]
                     (swap! database assoc db-id new-list)
                     (resp/created new-list "Location" (str "/todo/" db-id)))))))

(def list-view
  (-> t/transformer
      (update :id conj ::list-view)
      (update :with conj db-mixin entity-render-mixin err/default-error-handler)
      (assoc :op (fn [req]
                   (let [db-id (get-in req [:path-params :list-id])]
                     (get (:database req) db-id))))))

(def list-item-create
  (-> t/transformer
      (update :id conj ::list-item-create)
      (update :with conj db-mixin entity-render-mixin mw/json-body-mixin err/default-error-handler)
      (assoc :op (fn [req]
                   (let [list-id  (get-in req [:path-params :list-id])
                         nm       (get-in req [:json-params :name] "Unnamed")
                         item-id  (str (gensym "i"))
                         new-item (make-item nm)]
                     (swap! database assoc-in [list-id :items item-id] new-item)
                     (resp/created new-item))))))

;; --- Routes ---
(def routes
  #{["/todo"                    :post   list-create        :route-name ::list-create]
    ["/todo/:list-id"           :get    list-view          :route-name ::list-view]
    ["/todo/:list-id"           :post   list-item-create   :route-name ::list-item-create]})

;; --- Service ---
(def service-config
  {:routes routes
   :port   8890})

(defn -main [& _args]
  (-> service-config http/create-server http/start)
  (println "Todo API running on http://localhost:8890"))

;; --- Tests (no server needed) ---
(comment
  (def svc (ti-yong.http.service/service service-config))

  ;; Create a list
  (http/response-for svc :post "/todo?name=Groceries")
  ;; => {:status 201 :body {:name "Groceries" :items {}} :headers {"Location" "/todo/l42"}}

  ;; View it
  (http/response-for svc :get "/todo/l42")
  ;; => {:status 200 :body {:name "Groceries" :items {}} :headers {}}

  ;; The handler is inspectable:
  (:id list-create)
  ;; => [::r/root ::t/transformer ::list-create]

  (:with list-create)
  ;; => [db-mixin err/default-error-handler]

  ;; You can test the handler directly:
  (list-create {:uri "/todo" :request-method :post :headers {}
                :query-params {:name "Shopping"}})
  ;; => {:status 201 :body {:name "Shopping" :items {}} :headers {...}}
  )
```

### 5.3 Content Negotiation (`examples/content_negotiation.clj`)

Shows how `:out` stages on a response mixin handle serialization:

```clojure
(ns examples.content-negotiation
  (:require
   [ti-yong.http :as http]
   [ti-yong.http.response :as resp]
   [ti-yong.http.middleware :as mw]
   [ti-yong.alpha.transformer :as t]
   [clojure.data.json :as json]))

;; --- Content-type aware response mixin ---
(def negotiate-response
  "Serializes response body based on request Accept header."
  (-> t/transformer
      (update :id conj ::negotiate-response)
      (update :tf conj
              ::inject-accept
              (fn [env]
                ;; Carry the accept header from request into env for :out to use
                (let [accept (get-in (first (:args env)) [:headers "accept"] "text/plain")]
                  (assoc env :accept accept))))
      (update :out conj
              ::serialize-body
              (fn [response]
                ;; response here is the raw result from :op
                (if-not (map? response)
                  response
                  (let [accept (:accept response "text/plain") ;; won't work directly
                        body   (:body response)]
                    ;; For now, default to JSON
                    (-> response
                        (update :body json/write-str)
                        (assoc-in [:headers "Content-Type"] "application/json"))))))))

(def greet
  (-> t/transformer
      (update :id conj ::greet)
      (update :with conj negotiate-response mw/query-params-mixin)
      (assoc :op (fn [req]
                   (resp/ok {:greeting (str "Hello, "
                                            (get-in req [:query-params :name] "world")
                                            "!")})))))

(def routes
  #{["/greet" :get greet :route-name ::greet]})

(defn -main [& _args]
  (-> {:routes routes :port 8080} http/create-server http/start))
```

---

## Phase 6: Future Work (not in initial port)

### 6.1 Async Support

Add channel detection to pipeline reducers. When a stage returns a `core.async` channel, park and resume:

```clojure
;; In transformer-invoke, wrap each reduce step:
(defn maybe-async [result continuation]
  (if (satisfies? clojure.core.async.impl.protocols/ReadPort result)
    (go (continuation (<! result)))
    (continuation result)))
```

### 6.2 WebSocket / SSE Support

Define WebSocket/SSE as response types:

```clojure
(defn sse-response [event-fn]
  (-> response-tf
      (assoc :status 200
             :headers {"Content-Type" "text/event-stream"
                       "Cache-Control" "no-cache"}
             :body (->SSEBody event-fn))))
```

### 6.3 Error Unwinding

Add a proper `:tf-err` pipeline stage to `root.clj`:

```clojure
;; In transformer-invoke, add try/catch around pipeline execution
;; On exception, walk backward through accumulated :tf-err handlers
```

### 6.4 URL Generation (reverse routing)

```clojure
(defn url-for [routes route-name & {:keys [path-params query-params]}]
  ...)
```

### 6.5 Hot Reloading (dev mode)

```clojure
(def dev-routes
  "Wraps routes in a function for hot-reloading in dev."
  (fn [] (expand-routes #{...})))
```

---

## Summary: Pedestal vs ti-yong-http

| Concern | Pedestal | ti-yong-http |
|---------|----------|-------------|
| Handler | Function `request -> response` | Transformer map with `:op` |
| Interceptor | Map with `:enter`/`:leave`/`:error` | Transformer mixin with `:tf-pre`/`:out`/`:tf-end` |
| Composition | Manual `[i1 i2 i3]` vector | Declarative `:with [mixin-a mixin-b]` |
| Request | Plain Ring map | Transformer (callable, spec'd) |
| Response | Plain Ring map | Transformer (callable, spec'd) |
| Context | Separate mutable map flowing through interceptors | The handler IS the context |
| Deduplication | None (double-middleware is your problem) | Automatic via pipeline keyword IDs |
| Introspection | Requires debug-observer | `(:id handler)`, `(:specs handler)`, `(:with handler)` |
| Validation | Manual interceptors | Built-in `:specs` on handlers AND requests |
| Testing | `response-for` with mock servlets | Just call the transformer: `(handler request)` |
| Configuration | `::http/service-map` with 20+ keys | Plain map: `{:routes routes :port 8080}` |

---

## Implementation Order

1. **`request.clj`** + **`response.clj`** -- the two transformer primitives (~80 lines)
2. **`middleware.clj`** -- 6-8 standard mixins (~150 lines)
3. **`route.clj`** -- route expansion + router transformer (~100 lines)
4. **`error.clj`** -- error handler mixin (~40 lines)
5. **`service.clj`** -- service transformer (~30 lines)
6. **`adapter/ring.clj`** -- Ring adapter (~30 lines)
7. **`http.clj`** -- public API (~20 lines)
8. **Tests** -- mirror Pedestal's test patterns (~200 lines)
9. **`examples/`** -- hello world, todo API, content negotiation (~150 lines)

**Total: ~800 lines of implementation + ~350 lines of examples/tests**

---

## Dependencies

```clojure
;; deps.edn for ti-yong-http
{:paths ["src"]
 :deps {io.github.johnmn3/ti-yong {:git/url "https://github.com/johnmn3/ti-yong"
                                    :git/sha "..."}
        ring/ring-core            {:mvn/version "1.12.1"}
        ring/ring-jetty-adapter   {:mvn/version "1.12.1"}
        org.clojure/data.json     {:mvn/version "2.5.0"}}
 :aliases
 {:test {:extra-paths ["test"]
         :extra-deps  {io.github.cognitect-labs/test-runner
                       {:git/tag "v0.5.1"
                        :git/sha "dfb30dd"}}
         :main-opts   ["-m" "cognitect.test-runner"]
         :exec-fn     cognitect.test-runner.api/test}}}
```
