(ns ti-yong.http
  "Public API for ti-yong HTTP.
   Provides create-server, start, stop, response-for, and
   convenience wrappers for common middleware."
  (:require
   [ti-yong.http.service :as svc]
   [ti-yong.http.adapter.ring :as ring]
   [ti-yong.http.middleware :as mw]
   [ti-yong.http.error :as err]))

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
