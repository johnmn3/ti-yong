(ns hearth.alpha.service
  (:require
   [ti-yong.alpha.transformer :as t]
   [hearth.alpha.route :as route]))

;; Service transformer: the root pipeline that combines
;; a router with global middleware.

(defn service
  "Create a service transformer from a config map.
   Config keys:
     :routes - vector of route definition vectors
     :with   - vector of global middleware transformers (optional)"
  [{:keys [routes with]}]
  (let [router (route/router routes)]
    (cond-> (-> t/transformer
                (update :id conj ::service)
                (assoc :env-op (:env-op router)
                       :routes (:routes router)))
      (seq with)
      (update :with into with))))

(defn response-for
  "Test helper: invoke the service with a given method, path, and optional extras.
   Returns the response (whatever the service's pipeline produces)."
  ([svc method path]
   (response-for svc method path {}))
  ([svc method path extras]
   (-> svc
       (merge {:request-method method
               :uri path
               :headers {}
               :scheme :http
               :server-name "localhost"
               :server-port 80}
              extras)
       (apply []))))
