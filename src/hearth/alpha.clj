(ns hearth.alpha
  "Public API for ti-yong HTTP.
   Provides create-server, start, stop, response-for, and
   convenience wrappers for common middleware."
  (:require
   [hearth.alpha.service :as svc]
   [hearth.alpha.adapter.ring :as ring]
   [hearth.alpha.middleware :as mw]
   [hearth.alpha.error :as err]))

;; --- Server lifecycle ---

(defn create-server
  "Create a server config from a service map.
   Service map keys (namespaced under ::):
     ::routes - vector of route definition vectors
     ::port   - server port (default 8080)
     ::with   - global middleware transformers (optional)
     ::join?  - block the calling thread? (default true)"
  [service-map]
  (let [routes (::routes service-map)
        with   (::with service-map)
        port   (::port service-map 8080)
        join?  (::join? service-map true)
        svc    (svc/service (cond-> {:routes routes}
                              (seq with) (assoc :with with)))]
    (ring/create-server svc {:port port :join? join?})))

(defn start
  "Start the server. Requires ring.adapter.jetty on classpath.
   Returns the server instance."
  [server-cfg]
  (ring/start server-cfg))

(defn stop
  "Stop a running server."
  [server]
  (ring/stop server))

;; --- Testing ---

(defn response-for
  "Test helper: invoke a service-map with method, path, and optional extras.
   Does not start a server — runs the transformer pipeline directly."
  ([service-map method path]
   (response-for service-map method path {}))
  ([service-map method path extras]
   (let [routes (::routes service-map)
         with   (::with service-map)
         svc    (svc/service (cond-> {:routes routes}
                               (seq with) (assoc :with with)))]
     (svc/response-for svc method path extras))))

;; --- Middleware convenience wrappers ---

(defn logging-middleware
  "Create a logging middleware that appends log entries to the given atom."
  [log-atom]
  (mw/logging log-atom))

(defn content-type-middleware
  "Create a middleware that sets a default Content-Type header on responses."
  [ct]
  (mw/default-content-type ct))

(defn cors-middleware
  "Create a CORS middleware with the given options."
  [opts]
  (mw/cors opts))

(defn error-middleware
  "Create an error handler middleware (default 500 responses)."
  []
  err/error-handler)

(defn json-body-middleware
  "Middleware that parses JSON request bodies."
  []
  mw/json-body)

(defn json-response-middleware
  "Middleware that serializes map responses to JSON."
  []
  mw/json-response)

(defn query-params-middleware
  "Middleware that parses query strings."
  []
  mw/query-params)

(defn body-params-middleware
  "Middleware that parses request body (JSON or form-encoded) into :body-params."
  []
  mw/body-params)

(defn form-params-middleware
  "Middleware that parses form-encoded bodies into :form-params."
  []
  mw/form-params)

(defn keyword-params-middleware
  "Middleware that keywordizes string keys in parsed params."
  []
  mw/keyword-params)

(defn content-negotiation-middleware
  "Middleware that auto-serializes responses based on Accept header."
  []
  (mw/content-negotiation))

(defn html-body-middleware
  "Middleware that sets Content-Type to text/html on responses."
  []
  mw/html-body)

(defn json-body-response-middleware
  "Middleware that serializes response body to JSON and sets Content-Type."
  []
  mw/json-body-response)

(defn secure-headers-middleware
  "Middleware that adds security headers to responses.
   Pass a map to override defaults, or no args for Pedestal defaults."
  ([] (mw/secure-headers))
  ([overrides] (mw/secure-headers overrides)))

(defn method-param-middleware
  "Middleware that overrides :request-method from a query/form param.
   Only overrides POST requests. Default param: '_method'."
  ([] (mw/method-param))
  ([param-name] (mw/method-param param-name)))

(defn path-params-decoder-middleware
  "Middleware that URL-decodes path parameter values."
  []
  mw/path-params-decoder)

(defn cookies-middleware
  "Middleware that parses/writes cookies."
  []
  mw/cookies)

(defn session-middleware
  "Middleware for session management."
  ([] (mw/session))
  ([opts] (mw/session opts)))

(defn csrf-middleware
  "Middleware for CSRF protection."
  ([] (mw/csrf))
  ([opts] (mw/csrf opts)))

(defn multipart-params-middleware
  "Middleware that parses multipart/form-data bodies."
  ([] (mw/multipart-params))
  ([opts] (mw/multipart-params opts)))

(defn nested-params-middleware
  "Middleware that nests bracket-notation params."
  []
  mw/nested-params)

(defn head-method-middleware
  "Middleware that converts HEAD to GET and strips response body."
  []
  mw/head-method)

(defn not-modified-middleware
  "Middleware that returns 304 when ETag/Last-Modified match."
  []
  mw/not-modified)

(defn resource-middleware
  "Middleware that serves static classpath resources."
  [opts]
  (mw/resource opts))

(defn file-middleware
  "Middleware that serves static filesystem files."
  [opts]
  (mw/file opts))

(def format-sse-event
  "Format an event map as SSE text."
  mw/format-sse-event)
