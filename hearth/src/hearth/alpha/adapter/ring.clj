(ns hearth.alpha.adapter.ring
  (:require
   [hearth.alpha.service :as svc]
   [ti-yong.alpha.async :as async])
  (:import
   [java.util.concurrent CompletableFuture]
   [java.util.function Function]))

;; Ring adapter: converts a service transformer into a Ring handler
;; and provides server lifecycle management.
;; Supports both sync (1-arity) and async (3-arity) Ring handlers.

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
   Returns a fn that supports both sync (1-arity) and async (3-arity) Ring.
   Sync: returns a response map directly (blocks if result is deferred).
   Async: calls (respond response) or (raise exception) when done."
  [svc]
  (fn
    ;; Sync Ring handler (1-arity)
    ([ring-request]
     (let [resp (svc/response-for svc
                                   (:request-method ring-request)
                                   (:uri ring-request)
                                   (dissoc ring-request :request-method :uri))]
       (if (async/deferred? resp)
         (normalize-response (.get ^CompletableFuture (async/->deferred resp)))
         (normalize-response resp))))
    ;; Async Ring handler (3-arity)
    ([ring-request respond raise]
     (try
       (let [resp (svc/response-for svc
                                     (:request-method ring-request)
                                     (:uri ring-request)
                                     (dissoc ring-request :request-method :uri))]
         (if (async/deferred? resp)
           (-> (async/->deferred resp)
               (async/then (fn [r] (respond (normalize-response r))))
               (.exceptionally
                (reify Function
                  (apply [_ ex] (raise ex) nil))))
           (respond (normalize-response resp))))
       (catch Throwable t
         (raise t))))))

(defn create-server
  "Create a server config map from a service and options.
   Does not start the server - returns a config map with :handler, :port, etc.
   Use with ring.adapter.jetty/run-jetty or similar."
  [svc opts]
  (let [{:keys [port join? async?] :or {port 8080 join? true async? false}} opts]
    {:handler (service->handler svc)
     :port port
     :join? (if (contains? opts :join?) join? true)
     :async? async?}))

(defn start
  "Start a Jetty server from a server config map.
   Requires ring.adapter.jetty on the classpath.
   Returns the Jetty server instance."
  [server-cfg]
  (let [run-jetty (requiring-resolve 'ring.adapter.jetty/run-jetty)]
    (run-jetty (:handler server-cfg)
               {:port (:port server-cfg)
                :join? (:join? server-cfg)
                :async? (:async? server-cfg false)})))

(defn stop
  "Stop a running Jetty server instance."
  [server]
  (.stop server))
