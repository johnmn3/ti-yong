(ns hearth.alpha.adapter.ring
  (:require
   [hearth.alpha.service :as svc]))

;; Ring adapter: converts a service transformer into a Ring handler
;; and provides server lifecycle management.

(defn- normalize-response
  "Ensure the response has the required Ring keys."
  [resp]
  (if (map? resp)
    (-> resp
        (update :status #(or % 200))
        (update :headers #(or % {}))
        (update :body #(or % "")))
    {:status 200 :headers {} :body (str resp)}))

(defn service->handler
  "Convert a service transformer into a Ring handler function.
   Returns a fn that takes a Ring request map and returns a Ring response map."
  [svc]
  (fn [ring-request]
    (let [resp (svc/response-for svc
                                 (:request-method ring-request)
                                 (:uri ring-request)
                                 (dissoc ring-request :request-method :uri))]
      (normalize-response resp))))

(defn create-server
  "Create a server config map from a service and options.
   Does not start the server - returns a config map with :handler, :port, etc.
   Use with ring.adapter.jetty/run-jetty or similar."
  [svc opts]
  (let [{:keys [port join?] :or {port 8080 join? true}} opts]
    {:handler (service->handler svc)
     :port port
     :join? (if (contains? opts :join?) join? true)}))

(defn start
  "Start a Jetty server from a server config map.
   Requires ring.adapter.jetty on the classpath.
   Returns the Jetty server instance."
  [server-cfg]
  (let [run-jetty (requiring-resolve 'ring.adapter.jetty/run-jetty)]
    (run-jetty (:handler server-cfg)
               {:port (:port server-cfg)
                :join? (:join? server-cfg)})))

(defn stop
  "Stop a running Jetty server instance."
  [server]
  (.stop server))
